package com.nabd.hms.queue;

import com.nabd.hms.common.TenantScope;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** ACC-032: every minute, per tenant: open the items only time creates (unconfirmed soon, requests near their SLA, checkout waiting) and close the ones that settled. */
@Component
public class DeskExceptionSweepJob {

    private static final Logger log = LoggerFactory.getLogger(DeskExceptionSweepJob.class);

    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;
    private final DeskExceptions desk;

    DeskExceptionSweepJob(JdbcTemplate jdbc, TenantScope tenantScope, DeskExceptions desk) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.desk = desk;
    }

    @Scheduled(cron = "${app.desk-exceptions.sweep-cron:0 * * * * *}")
    @SchedulerLock(name = "desk-exception-sweep", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void scheduled() {
        runOnce();
    }

    /** Public for tests. One tenant failing never stops the others. Returns the number of changes. */
    public int runOnce() {
        int total = 0;
        for (UUID tenantId : jdbc.queryForList("SELECT id FROM tenants", UUID.class)) {
            try {
                total += tenantScope.run(tenantId, desk::sweep);
            } catch (RuntimeException e) {
                log.warn("desk exception sweep failed for tenant {}; the next run retries", tenantId, e);
            }
        }
        return total;
    }
}
