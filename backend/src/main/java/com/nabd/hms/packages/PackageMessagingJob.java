package com.nabd.hms.packages;

import com.nabd.hms.common.TenantScope;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * COM-044: the package expiry warnings (30/14/7 days), the grace notice and the next-session nudge. Hourly, one pass per clinic on
 * the clinic's own date (ClinicClock): every request is deduped (instance + tier + validity end, or instance + nudge date), so the
 * cadence only sets how soon after the clinic's midnight a warning is asked for. A failing clinic does not stop the others.
 */
@Component
public class PackageMessagingJob {

    private static final Logger log = LoggerFactory.getLogger(PackageMessagingJob.class);

    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;
    private final PackageMessagingService service;

    PackageMessagingJob(JdbcTemplate jdbc, TenantScope tenantScope, PackageMessagingService service) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.packages.messaging-interval-ms:3600000}", initialDelayString = "${app.packages.messaging-interval-ms:3600000}")
    @SchedulerLock(name = "package-messaging", lockAtMostFor = "PT50M")
    public void scheduled() {
        runOnce();
    }

    /** One pass over every clinic; returns the new requests made. */
    public int runOnce() {
        int n = 0;
        for (UUID tenantId : jdbc.queryForList("SELECT id FROM tenants", UUID.class)) {
            try {
                n += tenantScope.run(tenantId, service::runDue);
            } catch (RuntimeException e) {
                log.warn("package messaging failed for tenant {} ({}); the next run retries", tenantId, e.getClass().getSimpleName());
            }
        }
        return n;
    }
}
