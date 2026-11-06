package com.nabd.hms.reports;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** ADM-043: every few minutes, sends the daily summary of each clinic whose close time has passed (DailySummaryService decides). One clinic failing never stops the others; ids and counts only in logs. */
@Component
public class DailySummaryJob {

    private static final Logger log = LoggerFactory.getLogger(DailySummaryJob.class);

    private final JdbcTemplate jdbc;
    private final DailySummaryService service;

    DailySummaryJob(JdbcTemplate jdbc, DailySummaryService service) {
        this.jdbc = jdbc;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.daily-summary.interval-ms:300000}", initialDelayString = "${app.daily-summary.interval-ms:300000}")
    @SchedulerLock(name = "adm-daily-summary", lockAtMostFor = "PT30M")
    public void scheduled() { // ShedLock cannot lock a method returning a primitive
        run();
    }

    /** @return clinics whose summary went out on this run */
    public int run() {
        int sent = 0;
        for (UUID tenantId : jdbc.queryForList("SELECT id FROM tenants WHERE status IN ('trialing', 'active', 'overdue')", UUID.class)) {
            try {
                if (service.sendIfDue(tenantId)) {
                    sent++;
                }
            } catch (RuntimeException e) {
                log.warn("daily summary failed for tenant {}; the next run retries", tenantId, e);
            }
        }
        return sent;
    }
}
