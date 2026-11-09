package com.nabd.hms.pharmacy;

import com.nabd.hms.common.TenantScope;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** PHL-012: every minute, open worklist entries past their Rx validity become expired (and say so on the SSE topic). Reads already
 * treat them as expired in the gap, so the interval only bounds how soon the screens are told. */
@Component
public class PharmacyWorklistExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(PharmacyWorklistExpiryJob.class);

    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;
    private final PharmacyWorklistService service;

    PharmacyWorklistExpiryJob(JdbcTemplate jdbc, TenantScope tenantScope, PharmacyWorklistService service) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.pharmacy.worklist-expiry-interval-ms:60000}", initialDelayString = "${app.pharmacy.worklist-expiry-interval-ms:60000}")
    @SchedulerLock(name = "pharmacy-worklist-expiry", lockAtMostFor = "PT5M")
    public void scheduled() {
        runOnce();
    }

    /** One pass over all tenants; a failing tenant does not stop the others. Returns the number of entries expired. */
    public int runOnce() {
        int n = 0;
        for (UUID tenantId : jdbc.queryForList("SELECT id FROM tenants", UUID.class)) {
            try {
                Integer k = tenantScope.run(tenantId, service::expireDue);
                n += k == null ? 0 : k;
            } catch (RuntimeException e) {
                log.warn("worklist expiry failed for tenant {}; the next run retries", tenantId, e);
            }
        }
        return n;
    }
}
