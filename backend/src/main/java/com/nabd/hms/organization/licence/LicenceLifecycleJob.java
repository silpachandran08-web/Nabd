package com.nabd.hms.organization.licence;

import com.nabd.hms.common.TenantScope;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * ADM-032: every 10 minutes, one {@link LicenceLifecycleService#refresh()} per clinic on that clinic's own date (ClinicClock), so a
 * licence lapses at the clinic's midnight and a lapsed doctor is hidden within minutes. The pass is idempotent: the cadence
 * only sets how quickly a lapse shows, a reminder is still sent once per threshold. A failing clinic does not stop the others.
 */
@Component
public class LicenceLifecycleJob {

    private static final Logger log = LoggerFactory.getLogger(LicenceLifecycleJob.class);

    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;
    private final LicenceLifecycleService service;

    LicenceLifecycleJob(JdbcTemplate jdbc, TenantScope tenantScope, LicenceLifecycleService service) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.licence.refresh-interval-ms:600000}", initialDelayString = "${app.licence.refresh-interval-ms:600000}")
    @SchedulerLock(name = "licence-lifecycle", lockAtMostFor = "PT9M")
    public void scheduled() {
        runOnce();
    }

    /** One pass over every clinic (a clinic with no register row costs one empty query); returns the clinics processed. */
    public int runOnce() {
        int n = 0;
        for (UUID tenantId : jdbc.queryForList("SELECT id FROM tenants", UUID.class)) {
            try {
                tenantScope.run(tenantId, () -> service.refresh());
                n++;
            } catch (RuntimeException e) {
                log.warn("licence lifecycle failed for tenant {} ({}); the next run retries", tenantId, e.getClass().getSimpleName());
            }
        }
        return n;
    }
}
