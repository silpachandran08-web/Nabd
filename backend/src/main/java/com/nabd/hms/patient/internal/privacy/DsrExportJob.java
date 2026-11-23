package com.nabd.hms.patient.internal.privacy;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantScope;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * PLT-047: the privacy-request worker. Every 30 seconds it builds the waiting access packages, each clinic in its own tenant scope
 * (no RLS bypass), so a typical package is ready well inside ten minutes; hourly it sends the 75% reminders. A failed build rolls
 * back as a whole, leaves one event row and comes back through the claim's back-off. Logs carry ids only.
 */
@Component
public class DsrExportJob {

    private static final Logger log = LoggerFactory.getLogger(DsrExportJob.class);
    private static final int MAX_PER_CLINIC_PER_PASS = 20;

    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;
    private final DsrExportService exports;
    private final DsrService requests;
    private final ClinicClock clock;

    DsrExportJob(JdbcTemplate jdbc, TenantScope tenantScope, DsrExportService exports, DsrService requests, ClinicClock clock) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.exports = exports;
        this.requests = requests;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.dsr.export-interval-ms:30000}", initialDelayString = "${app.dsr.export-interval-ms:30000}")
    @SchedulerLock(name = "dsr-export", lockAtMostFor = "PT30M")
    public void scheduledExports() {
        runOnce();
    }

    @Scheduled(fixedDelayString = "${app.dsr.reminder-interval-ms:3600000}", initialDelayString = "${app.dsr.reminder-interval-ms:3600000}")
    @SchedulerLock(name = "dsr-reminders", lockAtMostFor = "PT30M")
    public void scheduledReminders() {
        remindOnce(clock.now());
    }

    /** One pass over every clinic; returns the packages delivered. */
    public int runOnce() {
        int done = 0;
        for (UUID tenant : jdbc.queryForList("SELECT id FROM tenants", UUID.class)) {
            done += runFor(tenant);
        }
        return done;
    }

    /** One clinic's waiting access requests. */
    public int runFor(UUID tenant) {
        int done = 0;
        for (int i = 0; i < MAX_PER_CLINIC_PER_PASS; i++) {
            DsrExportService.Claimed c = tenantScope.run(tenant, exports::claimNext);
            if (c == null) {
                break;
            }
            try {
                tenantScope.run(tenant, () -> exports.build(c));
                done++;
            } catch (RuntimeException e) {
                log.warn("privacy request {} of clinic {} not delivered ({})", c.id(), tenant, e.getClass().getSimpleName());
                tenantScope.run(tenant, () -> exports.failed(c, DsrExportService.describe(e)));
            }
        }
        return done;
    }

    /** One reminder pass at {@code now} (injected so a test can stand at any point of a request's period). */
    public int remindOnce(Instant now) {
        int reminded = 0;
        for (UUID tenant : jdbc.queryForList("SELECT id FROM tenants", UUID.class)) {
            reminded += tenantScope.run(tenant, () -> requests.remindDue(now));
        }
        return reminded;
    }
}
