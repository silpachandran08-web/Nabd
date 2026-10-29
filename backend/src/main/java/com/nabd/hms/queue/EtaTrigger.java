package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantScope;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.PreDestroy;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ACC-017: when to recompute. Any queue move or delay change for a doctor-day asks (after its transaction commits); asks for the same
 * doctor-day within 250 ms become one recompute. A 60 s tick refreshes every doctor-day with people in it, because ETAs move with the clock.
 */
@Component
public class EtaTrigger {

    static final long DEBOUNCE_MS = 250;
    private static final Logger log = LoggerFactory.getLogger(EtaTrigger.class);

    private record Key(UUID tenantId, UUID doctorId, LocalDate day) {
    }

    private final Set<Key> pending = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService pool = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "eta-recompute");
        t.setDaemon(true);
        return t;
    });
    private final TenantScope tenantScope;
    private final EtaService eta;
    private final EtaRepository repo;
    private final JdbcTemplate jdbc;
    private final ClinicClock clock;

    EtaTrigger(TenantScope tenantScope, EtaService eta, EtaRepository repo, JdbcTemplate jdbc, ClinicClock clock) {
        this.tenantScope = tenantScope;
        this.eta = eta;
        this.repo = repo;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Inside the transaction of the change: remembers the doctor-day and asks after commit. */
    void afterCommit(UUID tenantId, UUID doctorId, LocalDate day) {
        Key k = new Key(tenantId, doctorId, day);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    request(k);
                }
            });
        } else {
            request(k);
        }
    }

    private void request(Key k) {
        if (pending.add(k)) {
            pool.schedule(() -> run(k), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void run(Key k) {
        pending.remove(k); // a later ask during the run schedules the next one
        try {
            tenantScope.run(k.tenantId(), () -> eta.recompute(k.doctorId(), k.day()));
        } catch (RuntimeException e) {
            log.warn("ETA recompute failed for doctor {}; the next trigger or tick retries", k.doctorId(), e);
        }
    }

    // shortcut: one query per tenant per tick; at several hundred clinics per region move to a Valkey set of active doctor-days
    @Scheduled(fixedDelayString = "${app.eta.tick-ms:60000}")
    @SchedulerLock(name = "eta-tick", lockAtMostFor = "PT50S")
    public void tick() {
        for (UUID tenantId : jdbc.queryForList("SELECT id FROM tenants", UUID.class)) {
            try {
                tenantScope.run(tenantId, () -> {
                    LocalDate today = clock.clinicDate(tenantId);
                    for (UUID doctor : repo.activeDoctors(tenantId, today)) {
                        request(new Key(tenantId, doctor, today));
                    }
                });
            } catch (RuntimeException e) {
                log.warn("ETA tick failed for tenant {}", tenantId, e);
            }
        }
    }

    @PreDestroy
    void stop() {
        pool.shutdownNow();
    }
}
