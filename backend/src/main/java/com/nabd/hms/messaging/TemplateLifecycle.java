package com.nabd.hms.messaging;

import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * COM-013: what Meta's template webhooks (and an immediate approval at submission) do to a clinic's templates, inside the
 * caller's transaction with the clinic's tenant bound. A newer version that Meta approves takes over from the active one at
 * once, so sends switch on the next claim; a pause holds sends until paused_until; a re-categorisation changes the consent class
 * of every later send. Each change publishes comms.template.status_changed.v1 (owner banner, platform ops).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY) // always inside the caller's tenant transaction
class TemplateLifecycle {

    /** Meta pauses a template for 3 h the first time and 6 h the second; the third time it disables it. */
    static final Duration FIRST_PAUSE = Duration.ofHours(3);
    static final Duration SECOND_PAUSE = Duration.ofHours(6);
    private static final Set<String> CATEGORIES = Set.of("UTILITY", "MARKETING", "AUTHENTICATION");
    private static final Pattern CODE = Pattern.compile("^[A-Z_]{1,40}$");

    private final WhatsAppTemplateRepository repo;
    private final EventPublisher events;
    private final Clock clock;

    TemplateLifecycle(WhatsAppTemplateRepository repo, EventPublisher events, Clock clock) {
        this.repo = repo;
        this.events = events;
        this.clock = clock;
    }

    void onStatus(UUID tenantId, String metaTemplateId, String metaEvent, String reason, String pauseTitle) {
        repo.findByMetaForUpdate(tenantId, metaTemplateId).ifPresent(t -> {
            String to = WhatsAppTemplateService.statusOf(metaEvent);
            Instant pausedUntil = "paused".equals(to)
                    ? clock.instant().plus("SECOND_PAUSE".equalsIgnoreCase(pauseTitle) ? SECOND_PAUSE : FIRST_PAUSE) : null;
            repo.applyStatus(tenantId, t.id(), to, reason, pausedUntil);
            if (!to.equals(t.status())) {
                publish(tenantId, t, t.status(), to, reason, null);
            }
            activateIfNewer(tenantId, t.id());
        });
    }

    void onQuality(UUID tenantId, String metaTemplateId, String quality) {
        repo.findByMetaForUpdate(tenantId, metaTemplateId).ifPresent(t -> repo.applyQuality(tenantId, t.id(), quality));
    }

    /** Meta moved the template to another category (e.g. UTILITY to MARKETING): later sends follow the new consent class. */
    void onCategory(UUID tenantId, String metaTemplateId, String newCategory) {
        String c = newCategory == null ? "" : newCategory.toUpperCase();
        if (!CATEGORIES.contains(c)) {
            return;
        }
        repo.findByMetaForUpdate(tenantId, metaTemplateId).ifPresent(t -> {
            if (c.equals(t.effectiveCategory())) {
                return; // replay or no real change
            }
            repo.applyCategory(tenantId, t.id(), c);
            publish(tenantId, t, t.status(), t.status(), null, c);
        });
    }

    /** A sendable version newer than the active one becomes the active one; an older one approved late stays superseded. */
    void activateIfNewer(UUID tenantId, UUID id) {
        WhatsAppTemplateRepository.Template me = repo.find(tenantId, id).orElse(null);
        if (me == null || me.active() || !me.sendable()) {
            return;
        }
        WhatsAppTemplateRepository.Template current = repo.groupForUpdate(tenantId, me.name(), me.language()).stream()
                .filter(WhatsAppTemplateRepository.Template::active).findFirst().orElse(null);
        if (current == null) {
            repo.swapActive(tenantId, me.id(), me.id());
        } else if (current.version() < me.version()) {
            repo.swapActive(tenantId, current.id(), me.id());
        } else {
            repo.markSuperseded(tenantId, me.id(), current.id());
        }
    }

    private void publish(UUID tenantId, WhatsAppTemplateRepository.Template t, String from, String to, String reason, String category) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("templateId", t.id());
        data.put("key", t.catalogueKey() != null ? t.catalogueKey() : t.name());
        data.put("language", t.language());
        data.put("templateVersion", t.version());
        data.put("from", from);
        data.put("to", to);
        if (reason != null && CODE.matcher(reason).matches()) {
            data.put("reason", reason); // Meta's codes only; free text never travels in the event
        }
        if (category != null) {
            data.put("category", category);
        }
        events.publish(tenantId, DomainEventType.TEMPLATE_STATUS_CHANGED, t.id(), data);
    }
}
