package com.nabd.hms.messaging;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PLT-057: WhatsApp message to every active Owner of the clinic when an operator asks for support access. It carries the
 * operator's name, the reason (already pattern-redacted when the request was stored) and the scope: never a patient name
 * or a clinical value. The same event is the in-app request (the owner's GET /v1/support-access/requests reads the row
 * that was committed with it). Throws when the clinic has no approved {@value #TEMPLATE} template, so the outbox retries
 * and then dead-letters visibly instead of dropping the owner's notice.
 *
 * <p>shortcut: sent through WhatsAppMessageService directly; move to the shared notification contract (COM-004) when it merges.
 */
@Component
class SupportAccessRequestedNotifier implements OutboxEventHandler {

    static final String EVENT = "platform.support_access.requested.v1";
    static final String TEMPLATE = "support_access_request";

    private final JdbcTemplate jdbc;
    private final WhatsAppMessageService whatsApp;
    private final ObjectMapper mapper;

    SupportAccessRequestedNotifier(JdbcTemplate jdbc, WhatsAppMessageService whatsApp, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.whatsApp = whatsApp;
        this.mapper = mapper;
    }

    @Override
    public String eventType() {
        return EVENT;
    }

    @Override
    public void handle(OutboxEvent e) throws Exception {
        JsonNode p = mapper.readTree(e.payloadJson());
        String reason = p.path("reason").asString().replaceAll("\\s+", " ").strip();
        if (reason.length() > 120) {
            reason = reason.substring(0, 117) + "...";
        }
        String scope = "redacted_patient_view".equals(p.path("scope").asString()) ? "limited patient view" : "settings only";
        List<String> params = List.of(p.path("operatorName").asString(), reason.isEmpty() ? "none given" : reason,
                scope + ", " + p.path("minutes").asInt() + " min");
        List<String> phones = jdbc.queryForList("SELECT s.mobile_phone FROM staff s JOIN roles r ON r.id = s.role_id "
                + "WHERE s.tenant_id = ? AND r.built_in = true AND s.status = 'active' AND s.mobile_phone IS NOT NULL", String.class, e.tenantId());
        for (String phone : phones) {
            if (!phone.isBlank()) {
                whatsApp.queueTemplate(null, phone, TEMPLATE, "en", params);
            }
        }
    }
}
