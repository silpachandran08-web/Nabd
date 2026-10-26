package com.nabd.hms.queue;

import com.nabd.hms.common.TenantScope;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * ACC-009: every minute, a booking request past its review SLA raises one escalation (event to the reviewer role and the owner;
 * the Edge sends the patient one "still checking" message from the same event). The request is never auto-declined.
 */
@Component
public class BookingEscalationJob {

    private static final Logger log = LoggerFactory.getLogger(BookingEscalationJob.class);

    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;
    private final BookingRequestService service;

    BookingEscalationJob(JdbcTemplate jdbc, TenantScope tenantScope, BookingRequestService service) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.service = service;
    }

    @Scheduled(cron = "${app.booking-requests.escalation-cron:0 * * * * *}")
    @SchedulerLock(name = "booking-request-escalation", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void scheduled() {
        runOnce();
    }

    /** Public for tests. One tenant failing never stops the others. Returns the number of escalations raised. */
    public int runOnce() {
        int total = 0;
        for (UUID tenantId : jdbc.queryForList("SELECT id FROM tenants", UUID.class)) {
            try {
                total += tenantScope.run(tenantId, service::escalateDue);
            } catch (RuntimeException e) {
                log.warn("booking escalation failed for tenant {}; the next run retries", tenantId, e);
            }
        }
        return total;
    }
}
