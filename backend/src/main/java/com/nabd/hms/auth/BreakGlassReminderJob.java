package com.nabd.hms.auth;

import com.nabd.hms.common.TenantScope;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** PLT-013: one reminder (in-app + owner WhatsApp) per break-glass grant still unreviewed 24 h after it ended. */
@Component
public class BreakGlassReminderJob {

    private static final Logger log = LoggerFactory.getLogger(BreakGlassReminderJob.class);
    private final BreakGlassReviewService service;
    private final TenantScope tenantScope;

    BreakGlassReminderJob(BreakGlassReviewService service, TenantScope tenantScope) {
        this.service = service;
        this.tenantScope = tenantScope;
    }

    @Scheduled(fixedDelayString = "${app.break-glass.reminder-sweep-ms:300000}")
    @SchedulerLock(name = "break-glass-review-reminder", lockAtMostFor = "PT5M")
    public void sweep() {
        for (BreakGlassRepository.Due d : service.dueReminders()) {
            try {
                tenantScope.run(d.tenantId(), () -> service.remind(d.tenantId(), d.grantId()));
            } catch (RuntimeException e) { // one bad row must not stop the rest; nothing was stamped, so the next sweep retries it
                log.warn("break-glass review reminder for grant {} failed: {}", d.grantId(), e.toString());
            }
        }
    }
}
