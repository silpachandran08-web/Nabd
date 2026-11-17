package com.nabd.hms.pharmacy;

import com.nabd.hms.common.TenantScope;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** PHL-008: every 15 minutes, per clinic, raises the stock alerts (low stock the same day it happens) and sends the daily digest
 * once its clinic-time hour has passed. ShedLock keeps it to one instance; last_digest_day keeps the digest to one per clinic day.
 * One clinic failing never stops the others; ids only in logs. */
@Component
public class StockAlertJob {

    private static final Logger log = LoggerFactory.getLogger(StockAlertJob.class);

    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;
    private final StockAlertService service;

    StockAlertJob(JdbcTemplate jdbc, TenantScope tenantScope, StockAlertService service) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.pharmacy.stock-alert-interval-ms:900000}", initialDelayString = "${app.pharmacy.stock-alert-interval-ms:900000}")
    @SchedulerLock(name = "pharmacy-stock-alerts", lockAtMostFor = "PT30M")
    public void scheduled() { // ShedLock cannot lock a method returning a primitive
        run();
    }

    /** @return clinics whose digest went out on this run */
    public int run() {
        int sent = 0;
        // shortcut: every active clinic is scanned; clinics without stock rows insert nothing. Narrow to pharmacy modes if it gets slow.
        for (UUID tenantId : jdbc.queryForList("SELECT id FROM tenants WHERE status IN ('trialing', 'active', 'overdue')", UUID.class)) {
            try {
                if (runFor(tenantId)) {
                    sent++;
                }
            } catch (RuntimeException e) {
                log.warn("stock alerts failed for tenant {}; the next run retries", tenantId, e);
            }
        }
        return sent;
    }

    /** One clinic's pass in its own transaction: raise today's alerts, then the digest if it is due. */
    boolean runFor(UUID tenantId) {
        return Boolean.TRUE.equals(tenantScope.run(tenantId, () -> {
            service.raise(tenantId);
            return service.digestIfDue(tenantId);
        }));
    }
}
