package com.nabd.hms.reports;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantScope;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * ADM-041: hourly at :20 (after the ACC-019 ETA rollup at :12), refreshes every clinic's PREVIOUS day in the rollups so the
 * owner's morning overview is complete by 06:00 local whatever the zone. Between local midnight and 06:00 every run
 * recomputes (late stamps and the 04:00 ETA rollup are picked up); after 06:00 it only catches up a night that was missed.
 * One clinic failing never stops the others; ids only in logs.
 */
@Component
public class FlowRefreshJob {

    static final int READY_BY_HOUR = 6;
    private static final Logger log = LoggerFactory.getLogger(FlowRefreshJob.class);

    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;
    private final RollupProjector projector;
    private final FlowRollupRepository repo;
    private final ClinicClock clock;

    FlowRefreshJob(JdbcTemplate jdbc, TenantScope tenantScope, RollupProjector projector, FlowRollupRepository repo, ClinicClock clock) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.projector = projector;
        this.repo = repo;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.flow-rollup.refresh-cron:0 20 * * * *}")
    @SchedulerLock(name = "adm-flow-rollup-refresh", lockAtMostFor = "PT30M")
    public void scheduled() {
        for (UUID tenantId : jdbc.queryForList("SELECT id FROM tenants WHERE status IN ('trialing', 'active', 'overdue')", UUID.class)) {
            try {
                refreshYesterday(tenantId);
            } catch (RuntimeException e) {
                log.warn("flow rollup refresh failed for tenant {}; the next run retries", tenantId, e);
            }
        }
    }

    /** @return true when yesterday's rollup was recomputed on this run */
    public boolean refreshYesterday(UUID tenantId) {
        return tenantScope.run(tenantId, () -> {
            ZoneId zone = clock.zone(tenantId);
            LocalDate today = clock.today(zone);
            LocalDate yesterday = today.minusDays(1);
            boolean night = clock.now().atZone(zone).getHour() < READY_BY_HOUR;
            if (!night && repo.refreshedSince(tenantId, yesterday, ClinicClock.startOf(today, zone))) {
                return false;
            }
            projector.refresh(tenantId, yesterday);
            return true;
        });
    }
}
