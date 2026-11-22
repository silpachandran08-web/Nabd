package com.nabd.hms.pharmacy;

import com.nabd.hms.common.TenantScope;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** PHL-009: the nightly quarantine. Clinics sit in different time zones, so the pass runs hourly and each clinic's expired
 * batches (expiry date before its own today, ClinicClock) are quarantined within the hour after its midnight. A second pass
 * finds nothing to do; sales never needed it (FEFO already refuses expired stock). One clinic failing never stops the others. */
@Component
public class QuarantineJob {

    private static final Logger log = LoggerFactory.getLogger(QuarantineJob.class);

    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;
    private final QuarantineService service;

    QuarantineJob(JdbcTemplate jdbc, TenantScope tenantScope, QuarantineService service) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.pharmacy.quarantine-interval-ms:3600000}", initialDelayString = "${app.pharmacy.quarantine-interval-ms:3600000}")
    @SchedulerLock(name = "pharmacy-quarantine-expired", lockAtMostFor = "PT30M")
    public void scheduled() { // ShedLock cannot lock a method returning a primitive
        run();
    }

    /** @return batches quarantined on this run */
    public int run() {
        int n = 0;
        for (UUID tenantId : jdbc.queryForList("SELECT id FROM tenants WHERE status IN ('trialing', 'active', 'overdue')", UUID.class)) {
            try {
                n += runFor(tenantId);
            } catch (RuntimeException e) {
                log.warn("quarantine of expired batches failed for tenant {}; the next run retries", tenantId, e);
            }
        }
        return n;
    }

    /** One clinic's pass in its own transaction. */
    int runFor(UUID tenantId) {
        Integer n = tenantScope.run(tenantId, () -> service.quarantineExpired(tenantId));
        return n == null ? 0 : n;
    }
}
