package com.nabd.hms.messaging;

import com.nabd.hms.common.TenantScope;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * COM-003: settles templates stranded in 'submitting' (the API timed out, or crashed, between inserting the row and
 * recording Meta's answer). Older than {@link #STALE_MINUTES} min: ask Meta by name; if it has the template, adopt it
 * (its id and review status), if not, delete the row so the clinic can submit again. Runs every 5 min, so a stranded
 * template is gone within 15 min. A Meta outage just leaves the row for the next run. The Meta lookup runs with no
 * transaction open.
 */
@Component
public class TemplateReconciler {

    static final int STALE_MINUTES = 10;
    private static final Logger log = LoggerFactory.getLogger(TemplateReconciler.class);

    private final WhatsAppTemplateRepository repo;
    private final WhatsAppClient client;
    private final TenantScope tenantScope;

    TemplateReconciler(WhatsAppTemplateRepository repo, WhatsAppClient client, TenantScope tenantScope) {
        this.repo = repo;
        this.client = client;
        this.tenantScope = tenantScope;
    }

    @Scheduled(fixedDelayString = "${app.whatsapp.template-reconcile-ms:300000}")
    @SchedulerLock(name = "whatsapp-template-reconciler", lockAtMostFor = "PT4M")
    public void reconcile() {
        reconcile(STALE_MINUTES);
    }

    /** @return templates settled (adopted or deleted) */
    int reconcile(int staleMinutes) {
        int settled = 0;
        for (WhatsAppTemplateRepository.Stale s : repo.staleSubmitting(staleMinutes, 100)) {
            try {
                Optional<WhatsAppClient.Submission> found = client.findTemplate(s.metaName(), s.language());
                tenantScope.run(s.tenantId(), () -> found.isPresent()
                        ? repo.completeSubmission(s.tenantId(), s.id(), WhatsAppTemplateService.statusOf(found.get().status()), found.get().metaTemplateId())
                        : repo.deleteSubmitting(s.tenantId(), s.id()));
                settled++;
            } catch (RuntimeException e) { // Meta down or breaker open: try again next run
                log.warn("template {} left in 'submitting': {}", s.id(), e.toString());
            }
        }
        return settled;
    }
}
