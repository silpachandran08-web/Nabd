package com.nabd.hms.messaging;

import com.nabd.hms.patient.api.PatientConsentCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * COM-002: acts on an inbound STOP / START. Runs inside WhatsAppInboundService.process, i.e. in the inbound row's transaction
 * (bound to the clinic named by the message's reply context, or unbound when there is none), so the withdrawal, the
 * suppression, the consent event and the confirmation commit together with the row's 'done'.
 * A bare STOP withdraws 'messaging', 'Stop promotions' only 'marketing'. Both also record a phone-level suppression, which
 * covers a number with no patient record and is what the send handler checks for marketing templates.
 */
@Service
@Transactional
class OptOutService {

    static final String SOURCE_STOP = "whatsapp_stop";
    static final String SOURCE_START = "whatsapp_start";
    static final String CONFIRM_STOP = "optout_confirm";
    static final String CONFIRM_START = "optin_confirm";
    private static final Logger log = LoggerFactory.getLogger(OptOutService.class);

    private final OptOutRegistry registry;
    private final PatientConsentCommands consents;
    private final WhatsAppMessageService messages;
    private final WhatsAppTemplateRepository templates;

    OptOutService(OptOutRegistry registry, PatientConsentCommands consents, WhatsAppMessageService messages,
                  WhatsAppTemplateRepository templates) {
        this.registry = registry;
        this.consents = consents;
        this.messages = messages;
        this.templates = templates;
    }

    /** @param tenant the clinic the patient replied to, or null (shared number, no reply context). */
    void handle(UUID tenant, JsonNode message, String buttonPayload, long inboundId) {
        OptOutKeywords.Intent intent = OptOutKeywords.classify(textOf(message), buttonPayload);
        String phone = OptOutRegistry.digits(message.path("from").asString(""));
        if (intent == OptOutKeywords.Intent.NONE || phone.isEmpty()) {
            return;
        }
        if (intent == OptOutKeywords.Intent.START) {
            int lifted = registry.lift(tenant, phone, OptOutRegistry.MESSAGING);
            int changed = tenant == null ? 0 : consents.grantByPhone(phone, OptOutRegistry.MESSAGING, SOURCE_START, inboundId).changed();
            if (tenant != null && (lifted > 0 || changed > 0)) {
                confirm(tenant, phone, CONFIRM_START);
            }
            return;
        }
        String purpose = intent == OptOutKeywords.Intent.STOP_MARKETING ? OptOutRegistry.MARKETING : OptOutRegistry.MESSAGING;
        boolean recorded = registry.suppress(tenant, phone, purpose, SOURCE_STOP);
        // shortcut: a STOP with no reply context records the all-clinics suppression only; consent rows in every clinic that
        // knows the number would need a cross-tenant phone lookup. The send-time check still blocks every clinic.
        int changed = tenant == null ? 0 : consents.withdrawByPhone(phone, purpose, SOURCE_STOP, inboundId).changed();
        if (tenant != null && (recorded || changed > 0)) { // one confirmation per opt-out, not per repeated STOP
            confirm(tenant, phone, CONFIRM_STOP);
        }
    }

    /** Best effort: the clinic may not have the (approved, no-parameter) utility template yet; the opt-out stands regardless. */
    private void confirm(UUID tenant, String phone, String templateName) {
        Optional<WhatsAppTemplateRepository.Template> t = templates.findByName(tenant, templateName, "en");
        if (t.isEmpty() || !t.get().sendable() || t.get().paramCount() != 0) {
            log.info("no sendable '{}' template for tenant {}: confirmation not sent", templateName, tenant); // log-phi-ok: template name is a system constant, not patient data
            return;
        }
        messages.queueTemplate(null, "+" + phone, templateName, "en", List.of());
    }

    private static String textOf(JsonNode m) {
        JsonNode i = m.path("interactive");
        for (String s : new String[] {m.path("text").path("body").asString(null), m.path("button").path("text").asString(null),
                i.path("button_reply").path("title").asString(null), i.path("list_reply").path("title").asString(null)}) {
            if (s != null) {
                return s;
            }
        }
        return null;
    }
}
