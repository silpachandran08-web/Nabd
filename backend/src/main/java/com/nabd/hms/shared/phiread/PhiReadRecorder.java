package com.nabd.hms.shared.phiread;

import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * PLT-039: writes captured reads to phi_read_log on one background thread, so the request never waits on the insert,
 * then applies the read-volume rule (distinct patients per actor in the last hour, list views excluded). A full queue
 * runs the write on the caller instead of dropping it.
 */
@Component
public class PhiReadRecorder {

    public static final String VOLUME_ALERT_EVENT = "shared.phi_read.volume_alert.v1";
    static final int WINDOW_MINUTES = 60;

    /** One request's read. patientIds empty means "resolve from queueEntryLookup". No clinical content, identifiers only. */
    record Read(UUID tenantId, UUID actorId, UUID sessionId, boolean clinician, UUID breakGlassGrantId, String purpose,
                String resourceType, UUID resourceId, String endpoint, String ip, String requestId, List<UUID> patientIds,
                UUID queueEntryLookup, boolean list, long readAtMillis) {
    }

    private static final Logger log = LoggerFactory.getLogger(PhiReadRecorder.class);

    private final PhiReadRepository repo;
    private final TenantContext tenantContext;
    private final EventPublisher events;
    private final TransactionTemplate tx;
    private final int nonClinicianLimit;
    private final int clinicianLimit;
    private final Counter failures;
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(10_000),
            r -> {
                Thread t = new Thread(r, "phi-read-writer");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.CallerRunsPolicy());

    PhiReadRecorder(PhiReadRepository repo, TenantContext tenantContext, EventPublisher events, PlatformTransactionManager txManager,
                    MeterRegistry meters,
                    @Value("${app.phi-read.volume-limit.non-clinician:60}") int nonClinicianLimit,
                    @Value("${app.phi-read.volume-limit.clinician:150}") int clinicianLimit) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
        this.nonClinicianLimit = nonClinicianLimit;
        this.clinicianLimit = clinicianLimit;
        this.failures = Counter.builder("nabd.phi_read.write_failures").register(meters);
    }

    void submit(Read read) {
        writer.execute(() -> write(read));
    }

    /** PLT-041: another write (a secure-link open) on the same background thread, with the same full-queue rule. */
    void submitTask(Runnable task) {
        writer.execute(task);
    }

    void write(Read r) {
        try {
            tx.executeWithoutResult(s -> {
                tenantContext.set(r.tenantId());
                List<UUID> patients = r.patientIds().isEmpty() && r.queueEntryLookup() != null
                        ? repo.queueEntryPatient(r.tenantId(), r.queueEntryLookup()) : r.patientIds();
                if (patients.isEmpty()) {
                    return;
                }
                repo.insert(r, patients);
                if (!r.list() && r.actorId() != null) {
                    int distinct = repo.distinctPatientsLastHour(r.tenantId(), r.actorId());
                    if (distinct > (r.clinician() ? clinicianLimit : nonClinicianLimit)
                            && repo.insertAlert(r.tenantId(), r.actorId(), distinct, WINDOW_MINUTES)) {
                        events.publish(r.tenantId(), VOLUME_ALERT_EVENT, Map.of("tenantId", r.tenantId(), "actorId", r.actorId(),
                                "distinctPatients", distinct, "windowMinutes", WINDOW_MINUTES));
                    }
                }
            });
        } catch (RuntimeException e) {
            failures.increment(); // identifiers only in the message below; never the row content
            log.error("phi read log write failed (tenant {}, resource {}): {}", r.tenantId(), r.resourceType(), e.toString());
        }
    }

    /** Tests and shutdown: true once everything submitted so far is written. */
    boolean awaitIdle(long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (writer.getCompletedTaskCount() < writer.getTaskCount()) {
            if (System.currentTimeMillis() > end) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        writer.shutdown();
        writer.awaitTermination(5, TimeUnit.SECONDS);
    }
}
