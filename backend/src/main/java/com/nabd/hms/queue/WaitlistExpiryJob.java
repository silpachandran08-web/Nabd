package com.nabd.hms.queue;

import com.nabd.hms.common.TenantScope;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** ACC-026: every minute, expire waitlist offers past offer_expires_at and offer the slot to the next waiting patient (no screen needs to be open). */
@Component
public class WaitlistExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(WaitlistExpiryJob.class);

    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;
    private final WaitlistService service;

    WaitlistExpiryJob(JdbcTemplate jdbc, TenantScope tenantScope, WaitlistService service) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.waitlist.expiry-interval-ms:60000}", initialDelayString = "${app.waitlist.expiry-interval-ms:60000}")
    @SchedulerLock(name = "waitlist-offer-expiry", lockAtMostFor = "PT5M")
    public void scheduled() {
        runOnce();
    }

    /** One pass over all tenants; a failing tenant does not stop the others. Returns the doctors whose offers were closed. */
    public int runOnce() {
        int doctors = 0;
        for (UUID tenantId : jdbc.queryForList("SELECT id FROM tenants", UUID.class)) {
            try {
                Integer n = tenantScope.run(tenantId, () -> service.expireDue(tenantId));
                doctors += n == null ? 0 : n;
            } catch (RuntimeException e) {
                log.warn("waitlist expiry failed for tenant {}; the next run retries", tenantId, e);
            }
        }
        return doctors;
    }
}
