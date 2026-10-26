package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantScope;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** ACC-016: nightly robust recompute (median, p80, log-sigma) of every tenant's doctors over the last 30 days. */
@Component
public class ServiceTimeNightlyJob {

    static final int WINDOW_DAYS = 30;
    private static final Logger log = LoggerFactory.getLogger(ServiceTimeNightlyJob.class);

    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;
    private final ServiceTimeService service;
    private final ClinicClock clock;

    ServiceTimeNightlyJob(JdbcTemplate jdbc, TenantScope tenantScope, ServiceTimeService service, ClinicClock clock) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.service-time.nightly-cron:0 30 2 * * *}")
    @SchedulerLock(name = "service-time-nightly", lockAtMostFor = "PT30M")
    public void scheduled() {
        for (UUID tenantId : jdbc.queryForList("SELECT id FROM tenants", UUID.class)) {
            try {
                log.debug("service time recompute: tenant {} wrote {} rows", tenantId, recompute(tenantId));
            } catch (RuntimeException e) {
                log.warn("service time recompute failed for tenant {}; the next night retries", tenantId, e);
            }
        }
    }

    public int recompute(UUID tenantId) {
        return tenantScope.run(tenantId, () -> service.recompute(clock.now().minus(WINDOW_DAYS, ChronoUnit.DAYS)));
    }
}
