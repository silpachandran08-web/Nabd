package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * ACC-019: what the ETA engine (ACC-017) calls whenever it shows a patient a time: at check-in and when leave-home is sent.
 * The actual call time is stamped by QueueEventRepository when the visit reaches in_consult. Either id may be null
 * (leave-home precedes the queue entry; a walk-in has no appointment), not both.
 */
@Component
public class EtaPredictionLog {

    private final EtaAccuracyRepository repo;
    private final TenantContext tenantContext;
    private final ClinicClock clock;

    EtaPredictionLog(EtaAccuracyRepository repo, TenantContext tenantContext, ClinicClock clock) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    /** Runs in the caller's tenant transaction. horizonMin = minutes from now to p50, as shown to the patient. */
    @Transactional
    public void record(UUID queueEntryId, UUID appointmentId, UUID doctorId, Instant p50, Instant p80) {
        if (queueEntryId == null && appointmentId == null) {
            throw new IllegalArgumentException("a prediction needs a queue entry or an appointment");
        }
        UUID tenantId = tenantContext.current();
        Instant now = clock.now();
        repo.insert(tenantId, queueEntryId, appointmentId, doctorId, clock.today(tenantId), now, p50, p80.isBefore(p50) ? p50 : p80,
                (int) Math.max(0, java.time.Duration.between(now, p50).toMinutes()));
    }
}
