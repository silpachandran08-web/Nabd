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

import java.time.LocalDate;
import java.util.UUID;

/**
 * ACC-019: hourly, rolls up every tenant's PREVIOUS clinic day (tenant time zone) into eta_accuracy_daily. Idempotent upsert,
 * so late call stamps are picked up and yesterday's figures are in place well before 06:00 local whatever the zone.
 */
@Component
public class EtaAccuracyJob {

    private static final Logger log = LoggerFactory.getLogger(EtaAccuracyJob.class);

    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;
    private final EtaAccuracyRepository repo;
    private final ClinicClock clock;

    EtaAccuracyJob(JdbcTemplate jdbc, TenantScope tenantScope, EtaAccuracyRepository repo, ClinicClock clock) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.repo = repo;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.eta-accuracy.rollup-cron:0 12 * * * *}")
    @SchedulerLock(name = "eta-accuracy-rollup", lockAtMostFor = "PT30M")
    public void scheduled() {
        for (UUID tenantId : jdbc.queryForList("SELECT id FROM tenants", UUID.class)) {
            try {
                int rows = rollupYesterday(tenantId);
                log.debug("eta accuracy rollup: tenant {} wrote {} rows", tenantId, rows);
            } catch (RuntimeException e) {
                log.warn("eta accuracy rollup failed for tenant {}; the next run retries", tenantId, e);
            }
        }
    }

    public int rollupYesterday(UUID tenantId) {
        return tenantScope.run(tenantId, () -> rollup(tenantId, clock.today(tenantId).minusDays(1)));
    }

    /** Public for backfills and tests: rolls one clinic day of the bound tenant. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int rollup(UUID tenantId, LocalDate day) {
        repo.ensurePartitions(clock.today(tenantId));
        return repo.rollup(tenantId, day);
    }
}
