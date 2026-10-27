package com.nabd.hms.messaging;

import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.FieldCipher;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.common.DomainEventType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * COM-001: works one claimed inbound row off. The effect (status move, template update, event) and the fenced finish run in
 * ONE transaction, so a crash or a lost lease rolls the effect back and the next claim redoes it: exactly once by effect.
 * An exception rolls back and the caller hands the row back with {@link #release}.
 */
@Service
@Transactional
class WhatsAppInboundService {

    static final int MAX_ATTEMPTS = 5; // shortcut: retries are immediate (next poll), no backoff; add next_attempt_at if a bad row ever hot-loops
    private static final Set<String> TRACKED = Set.of("delivered", "read", "failed");
    private static final Pattern SAFE_PAYLOAD = Pattern.compile("^[A-Za-z0-9_.: -]{1,64}$");
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9=_.-]{1,128}$");
    private static final Pattern DIGITS = Pattern.compile("^[0-9]{1,32}$");

    private final WhatsAppInboundRepository repo;
    private final WhatsAppTemplateRepository templates;
    private final FieldCipher cipher;
    private final EventPublisher events;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TenantScope scope;
    private final OptOutService optOut;
    private final java.util.List<com.nabd.hms.shared.notify.InboundButtonListener> buttonListeners;

    WhatsAppInboundService(WhatsAppInboundRepository repo, WhatsAppTemplateRepository templates, FieldCipher cipher,
                           EventPublisher events, ObjectMapper mapper, Clock clock, TenantScope scope, OptOutService optOut,
                           java.util.List<com.nabd.hms.shared.notify.InboundButtonListener> buttonListeners) {
        this.buttonListeners = buttonListeners;
        this.scope = scope;
        this.optOut = optOut;
        this.repo = repo;
        this.templates = templates;
        this.cipher = cipher;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
    }

    static FieldCipher.Aad aad(String dedupeKey) {
        return FieldCipher.Aad.of("platform", "whatsapp_inbound_events", "body_enc", dedupeKey);
    }

    @Transactional(rollbackFor = Exception.class)
    void process(WhatsAppInboundRepository.Row row) throws Exception {
        if (row.attempts() > MAX_ATTEMPTS) {
            finish(row, "dead", null, "max_attempts");
            return;
        }
        Optional<byte[]> plain = row.body() == null ? Optional.empty() : cipher.tryDecrypt(row.body(), aad(row.dedupeKey()));
        if (plain.isEmpty()) {
            finish(row, "dead", null, "no_body");
            return;
        }
        JsonNode body = mapper.readTree(new String(plain.get(), StandardCharsets.UTF_8));
        UUID tenant = switch (body.path("kind").asString("")) {
            case "status" -> status(body.path("item"));
            case "template" -> template(row.field(), body.path("value"));
            case "message" -> message(row, body.path("item"));
            default -> null;
        };
        finish(row, handled(body) ? "done" : "ignored", tenant, null);
    }

    /** Terminal 'ignored' for kinds nobody consumes (account_update, a 'sent' status, ...); a row is never silently lost. */
    private static boolean handled(JsonNode body) {
        String kind = body.path("kind").asString("");
        if (kind.equals("status")) {
            return TRACKED.contains(body.path("item").path("status").asString(""));
        }
        return kind.equals("message") || kind.equals("template");
    }

    java.util.List<WhatsAppInboundRepository.Row> claim(int limit, int leaseSeconds) {
        return repo.claim(limit, leaseSeconds);
    }

    void release(WhatsAppInboundRepository.Row row, String errorCode) {
        repo.finish(row, row.attempts() >= MAX_ATTEMPTS ? "dead" : "pending", null, errorCode);
    }

    int purge(int bodyDays, int keyDays, int batch) {
        return repo.purge(bodyDays, keyDays, batch);
    }

    @Transactional(readOnly = true)
    WhatsAppInboundRepository.Stats stats() {
        return repo.stats();
    }

    private void finish(WhatsAppInboundRepository.Row row, String status, UUID tenant, String error) {
        if (!repo.finish(row, status, tenant, error)) {
            throw new IllegalStateException("inbound event " + row.id() + " claim lost"); // rolls the effect back
        }
    }

    /** Forward-only (SQL rule of apply_whatsapp_status): a late 'delivered' after 'read' moves nothing and publishes nothing. */
    private UUID status(JsonNode s) {
        String status = s.path("status").asString("");
        if (!TRACKED.contains(status)) {
            return null;
        }
        JsonNode err = s.path("errors").path(0);
        String errorText = err.isMissingNode() ? null : err.path("code").asString() + ": " + err.path("title").asString();
        Optional<WhatsAppInboundRepository.StatusMove> move = repo.applyStatus(s.path("id").asString(), status, errorText);
        if (move.isEmpty()) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageId", move.get().messageId());
        data.put("channel", "whatsapp");
        data.put("status", status);
        String code = err.path("code").asString("");
        if (DIGITS.matcher(code).matches() && code.length() <= 10) {
            data.put("errorCode", code);
        }
        data.put("at", at(s.path("timestamp").asString("")).toString());
        events.publish(move.get().tenantId(), DomainEventType.MESSAGE_STATUS_CHANGED, move.get().messageId(), data);
        return move.get().tenantId();
    }

    private UUID template(String field, JsonNode v) {
        String id = v.path("message_template_id").asString();
        if ("message_template_quality_update".equals(field)) {
            templates.applyUpdate(id, null, null, v.path("new_quality_score").asString("UNKNOWN"));
        } else {
            templates.applyUpdate(id, WhatsAppTemplateService.statusOf(v.path("event").asString()),
                    v.path("reason").isNull() || "NONE".equals(v.path("reason").asString())
                            ? null : v.path("reason").asString(null),
                    null);
        }
        return null;
    }

    /** An inbound patient message. A reply carries the wamid of our message (context.id), which names the clinic; a message
     * with no context has no clinic and publishes no event (the outbox needs a tenant). The text is never read here. */
    private UUID message(WhatsAppInboundRepository.Row row, JsonNode m) {
        String replyTo = m.path("context").path("id").asString("");
        UUID tenant = replyTo.isEmpty() ? null : repo.messageTenant(replyTo).orElse(null);
        String payload = buttonPayload(m);
        if (tenant == null) {
            optOut.handle(null, m, payload, row.id());
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inboundId", row.id());
        if (row.numberId() != null && DIGITS.matcher(row.numberId()).matches()) {
            data.put("senderNumberId", row.numberId());
        }
        data.put("kind", kind(m.path("type").asString("")));
        if (payload != null && SAFE_PAYLOAD.matcher(payload).matches()) {
            data.put("buttonPayload", payload);
        }
        if (SAFE_ID.matcher(replyTo).matches()) {
            data.put("replyToWamid", replyTo);
        }
        data.put("receivedAt", at(m.path("timestamp").asString("")).toString());
        scope.run(tenant, () -> { // binds this transaction to the clinic, which the consent and send calls below read
            events.publish(tenant, DomainEventType.INBOUND_RECEIVED, new UUID(0, row.id()), data);
            optOut.handle(tenant, m, payload, row.id());
            if (payload != null && SAFE_PAYLOAD.matcher(payload).matches() && !buttonListeners.isEmpty()) { // ACC-024: delay-message buttons
                repo.patientOfMessage(replyTo).ifPresent(p -> buttonListeners.forEach(l -> l.onButton(p, payload)));
            }
        });
        return tenant;
    }

    static String buttonPayload(JsonNode m) {
        JsonNode i = m.path("interactive");
        String p = m.path("button").path("payload").asString(null);
        if (p == null) {
            p = i.path("button_reply").path("id").asString(null);
        }
        return p != null ? p : i.path("list_reply").path("id").asString(null);
    }

    static String kind(String type) {
        return switch (type) {
            case "text" -> "text";
            case "button" -> "button";
            case "interactive" -> "interactive";
            case "image", "video", "audio", "document", "sticker" -> "media";
            case "location" -> "location";
            default -> "other";
        };
    }

    private Instant at(String epochSeconds) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(epochSeconds));
        } catch (NumberFormatException e) {
            return clock.instant();
        }
    }
}
