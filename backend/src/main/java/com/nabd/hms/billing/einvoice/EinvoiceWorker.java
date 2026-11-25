package com.nabd.hms.billing.einvoice;

import com.nabd.hms.common.ClinicClock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * FIN-011: the e-invoice retry worker. Every 15 s it asks which clinics have a submission due (einvoice_due_tenants: switch on,
 * status pending, next_attempt_at reached) and runs each clinic in its own scope, so a new B2B invoice reaches the IRP well
 * inside a minute and a clinic with the switch off is never even looked at. Without an IRP adapter it does nothing.
 */
@Component
public class EinvoiceWorker {

    private static final Logger log = LoggerFactory.getLogger(EinvoiceWorker.class);

    private final JdbcTemplate jdbc;
    private final EinvoiceService service;
    private final ClinicClock clock;

    EinvoiceWorker(JdbcTemplate jdbc, EinvoiceService service, ClinicClock clock) {
        this.jdbc = jdbc;
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.billing.einvoice-worker-ms:15000}", initialDelayString = "${app.billing.einvoice-worker-ms:15000}")
    @SchedulerLock(name = "einvoice-worker", lockAtMostFor = "PT10M")
    void run() {
        runOnce();
    }

    /** Returns the number of IRP calls made. */
    public int runOnce() {
        if (!service.adapterAvailable()) return 0;
        int calls = 0;
        for (UUID tenantId : jdbc.queryForList("SELECT tenant_id FROM einvoice_due_tenants(?)", UUID.class, Timestamp.from(clock.now()))) {
            try {
                calls += service.processDue(tenantId);
            } catch (RuntimeException e) {
                log.error("e-invoice worker failed for tenant {}: {}", tenantId, e.getClass().getSimpleName()); // log-phi-ok: exception class name
            }
        }
        return calls;
    }
}
