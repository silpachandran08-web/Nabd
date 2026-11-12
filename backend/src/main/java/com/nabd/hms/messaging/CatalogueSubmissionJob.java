package com.nabd.hms.messaging;

import com.nabd.hms.common.TenantScope;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * COM-012: hands queued catalogue templates (and COM-013 new template versions) to Meta, a limited number per run so Meta's per-WABA template-creation
 * limit is respected. A 429 or 5xx leaves the row queued with exponential backoff (30 s doubling, capped at 1 h); a
 * 4xx verdict marks it rejected with Meta's reason. Meta calls run with no transaction open (OPS-012).
 */
@Component
public class CatalogueSubmissionJob {

    private static final Logger log = LoggerFactory.getLogger(CatalogueSubmissionJob.class);
    private static final int MAX_ATTEMPTS = 8;

    private final TemplateCatalogueRepository repo;
    private final WhatsAppClient client;
    private final TenantScope tenantScope;
    private final Clock clock;
    private final int perRun;
    private final TemplateLifecycle lifecycle;
    private final WhatsAppTemplateService templates;

    CatalogueSubmissionJob(TemplateCatalogueRepository repo, WhatsAppClient client, TenantScope tenantScope, Clock clock,
                           @Value("${app.templates.catalogue-submit-per-run:20}") int perRun, TemplateLifecycle lifecycle,
                           WhatsAppTemplateService templates) {
        this.lifecycle = lifecycle;
        this.templates = templates;
        this.repo = repo;
        this.client = client;
        this.tenantScope = tenantScope;
        this.clock = clock;
        this.perRun = perRun;
    }

    @Scheduled(fixedDelayString = "${app.templates.catalogue-submit-ms:30000}", initialDelayString = "${app.templates.catalogue-submit-ms:30000}")
    @SchedulerLock(name = "catalogue-template-submit", lockAtMostFor = "PT5M")
    public void scheduled() {
        runOnce();
    }

    /** @return templates handed to Meta (or refused) in this run. COM-013: a clinic rename first queues new header versions. */
    public int runOnce() {
        templates.resyncHeaders(perRun);
        int left = perRun;
        int done = 0;
        for (UUID tenantId : repo.dueTenants(perRun)) {
            if (left <= 0) {
                break;
            }
            List<TemplateCatalogueRepository.Due> due = tenantScope.run(tenantId, () -> repo.due(tenantId, perRun));
            for (TemplateCatalogueRepository.Due d : due) {
                if (left-- <= 0) {
                    break;
                }
                done += submit(tenantId, d) ? 1 : 0;
            }
        }
        return done;
    }

    private boolean submit(UUID tenantId, TemplateCatalogueRepository.Due d) {
        try {
            WhatsAppClient.Submission sub = client.createTemplate(d.metaName(), d.language(), d.category(), d.headerText(), d.body(), d.examples());
            tenantScope.run(tenantId, () -> {
                repo.submitted(tenantId, d.id(), WhatsAppTemplateService.statusOf(sub.status()), sub.metaTemplateId());
                lifecycle.activateIfNewer(tenantId, d.id()); // a new version Meta approved at once takes over now
            });
            return true;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError() && e.getStatusCode().value() != 429) {
                tenantScope.run(tenantId, () -> repo.refused(tenantId, d.id(), "Meta refused the template (HTTP " + e.getStatusCode().value() + ")"));
                return true;
            }
            backoff(tenantId, d);
        } catch (RuntimeException e) { // timeout, open circuit, anything transient: retry later
            backoff(tenantId, d);
        }
        return false;
    }

    private void backoff(UUID tenantId, TemplateCatalogueRepository.Due d) {
        int attempts = d.attempts() + 1;
        if (attempts >= MAX_ATTEMPTS) {
            tenantScope.run(tenantId, () -> repo.refused(tenantId, d.id(), "Submission to Meta kept failing; platform ops will retry"));
            log.warn("catalogue template {} gave up after {} attempts", d.metaName(), attempts); // log-phi-ok: template name is config
            return;
        }
        long seconds = Math.min(3600L, 30L << (attempts - 1));
        tenantScope.run(tenantId, () -> repo.retryLater(tenantId, d.id(), attempts, clock.instant().plus(Duration.ofSeconds(seconds))));
    }
}
