package com.nabd.hms.auth;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import com.nabd.hms.messaging.WhatsAppMessageService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/** PLT-012: owner notification, as two outbox consumers so a missing WhatsApp template never costs the in-app alert.
 * Message text carries the staff name and time only: no patient name, no diagnosis, no reason text. */
final class BreakGlassOwnerNotifier {
    private BreakGlassOwnerNotifier() {
    }

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd MMM HH:mm");

    private static String when(ClinicClock clock, OutboxEvent e, JsonNode p) {
        return FMT.format(Instant.parse(p.path("activatedAt").asString()).atZone(clock.zone(e.tenantId())));
    }

    @Component
    static class InApp implements OutboxEventHandler {
        private final BreakGlassRepository repo;
        private final ClinicClock clock;
        private final ObjectMapper mapper;

        InApp(BreakGlassRepository repo, ClinicClock clock, ObjectMapper mapper) {
            this.repo = repo;
            this.clock = clock;
            this.mapper = mapper;
        }

        @Override
        public String eventType() {
            return BreakGlassPolicy.ACTIVATED_EVENT;
        }

        @Override
        public void handle(OutboxEvent e) throws Exception {
            JsonNode p = mapper.readTree(e.payloadJson());
            UUID grantId = UUID.fromString(p.path("grantId").asString());
            String message = "Emergency access used by " + repo.staffName(UUID.fromString(p.path("staffId").asString()))
                    + " at " + when(clock, e, p) + ". Review in Nabd.";
            for (BreakGlassRepository.Owner o : repo.owners(e.tenantId())) {
                repo.insertAlert(e.tenantId(), grantId, o.id(), message);
            }
            repo.markNotified(grantId);
        }
    }

    @Component
    static class WhatsApp implements OutboxEventHandler {
        private final BreakGlassRepository repo;
        private final WhatsAppMessageService whatsApp;
        private final ClinicClock clock;
        private final ObjectMapper mapper;

        WhatsApp(BreakGlassRepository repo, WhatsAppMessageService whatsApp, ClinicClock clock, ObjectMapper mapper) {
            this.repo = repo;
            this.whatsApp = whatsApp;
            this.clock = clock;
            this.mapper = mapper;
        }

        @Override
        public String eventType() {
            return BreakGlassPolicy.OWNER_WHATSAPP_EVENT;
        }

        @Override
        public void handle(OutboxEvent e) throws Exception {
            JsonNode p = mapper.readTree(e.payloadJson());
            String name = repo.staffName(UUID.fromString(p.path("staffId").asString()));
            String time = when(clock, e, p);
            for (BreakGlassRepository.Owner o : repo.owners(e.tenantId())) {
                if (o.phone() != null && !o.phone().isBlank()) {
                    // throws if the tenant has no approved emergency_access_used template: the outbox retries, then dead-letters visibly
                    whatsApp.queueTemplate(null, o.phone(), BreakGlassPolicy.WHATSAPP_TEMPLATE, "en", List.of(name, time));
                }
            }
        }
    }
}
