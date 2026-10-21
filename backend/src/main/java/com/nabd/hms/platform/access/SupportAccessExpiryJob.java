package com.nabd.hms.platform.access;

import com.nabd.hms.common.TenantScope;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** PLT-057: closes requests nobody answered in 24 h and windows that ran out, and writes "Session ended" / "Request expired"
 * to the clinic's audit log. Access itself is cut off by the time check on every use, not by this job. */
@Component
public class SupportAccessExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(SupportAccessExpiryJob.class);
    private final SupportAccessService service;
    private final TenantScope tenantScope;

    SupportAccessExpiryJob(SupportAccessService service, TenantScope tenantScope) {
        this.service = service;
        this.tenantScope = tenantScope;
    }

    @Scheduled(fixedDelayString = "${app.support-access.sweep-ms:30000}")
    @SchedulerLock(name = "support-access-expiry", lockAtMostFor = "PT5M")
    public void sweep() {
        for (SupportAccessService.Due d : service.dueGrants()) {
            try {
                tenantScope.run(d.clinic(), () -> service.expire(d.id()));
            } catch (RuntimeException e) { // one bad row must not stop the rest; it is retried on the next sweep
                log.warn("support access expiry of {} failed: {}", d.id(), e.toString());
            }
        }
    }
}
