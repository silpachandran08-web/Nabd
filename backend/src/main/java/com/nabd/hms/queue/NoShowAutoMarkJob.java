package com.nabd.hms.queue;

import com.nabd.hms.common.TenantScope;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** ACC-025: every minute, mark scheduled bookings nobody arrived for once start + grace has passed (tenants whose policy has auto-mark on). */
@Component
public class NoShowAutoMarkJob {

    private static final Logger log = LoggerFactory.getLogger(NoShowAutoMarkJob.class);

    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;
    private final NoShowService service;

    NoShowAutoMarkJob(JdbcTemplate jdbc, TenantScope tenantScope, NoShowService service) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.noshow.interval-ms:60000}", initialDelayString = "${app.noshow.interval-ms:60000}")
    @SchedulerLock(name = "noshow-auto-mark", lockAtMostFor = "PT5M")
    public void scheduled() {
        runOnce();
    }

    /** One pass over all tenants; a failing tenant does not stop the others. Returns how many appointments were marked. */
    public int runOnce() {
        int marked = 0;
        for (UUID tenantId : jdbc.queryForList("SELECT id FROM tenants", UUID.class)) {
            try {
                Integer n = tenantScope.run(tenantId, service::autoMarkDue);
                marked += n == null ? 0 : n;
            } catch (RuntimeException e) {
                log.warn("no-show auto-mark failed for tenant {}; the next run retries", tenantId, e);
            }
        }
        return marked;
    }
}
