package com.nabd.comms.sms;

import com.nabd.comms.events.StreamEventPublisher;
import com.nabd.comms.gate.SendGate;
import com.nabd.comms.inbox.FieldCrypto;
import com.nabd.comms.inbox.InboxException;
import com.nabd.comms.provider.Provider;
import com.nabd.comms.send.StatusApplier;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * COM-026: SMS through the Indian BSP. A send is refused locally, before anything reaches the BSP, when this deployment is not India
 * (SMS_DISABLED_FOR_REGION: GCC sender registration is out of scope, T-309) or when the text matches no active DLT template of the
 * tenant or the Nabd-shared registration (DLT_TEMPLATE_MISMATCH). The consent gate runs at send time like WhatsApp. Delivery reports
 * are persisted then acked (inbound_events), and applied here asynchronously: status moves forward on outbound_messages and
 * comms.message.status_changed.v1 (channel sms) is published. Phone numbers are encrypted at rest; text and numbers are never logged.
 */
public class SmsService {

    public record SendRequest(String to, String text, UUID patientRef, UUID requestId) {}

    public record Sent(UUID messageId, String status) {}

    private record Prepared(Sent sent, Template template) {}

    record Template(UUID id, String key, String language, String entityId, String dltTemplateId, String header, String category, String body) {}

    static final Pattern INDIAN_MOBILE = Pattern.compile("\\+91[6-9][0-9]{9}");
    static final int MAX_TEXT = 2000;
    /** A report may beat our own 'sent' update (fast BSP): it stays pending this many passes before it is ignored. */
    static final int DLR_MAX_ATTEMPTS = 10;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final FieldCrypto crypto;
    private final SmsProvider provider;
    private final SendGate gate;
    private final StatusApplier statuses;
    private final StreamEventPublisher events;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final String region;

    public SmsService(JdbcTemplate jdbc, TransactionTemplate tx, FieldCrypto crypto, SmsProvider provider, SendGate gate, StreamEventPublisher events,
                      ObjectMapper json, MeterRegistry metrics, Clock clock, String region) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.crypto = crypto;
        this.provider = provider;
        this.gate = gate;
        this.statuses = new StatusApplier(jdbc);
        this.events = events;
        this.json = json;
        this.metrics = metrics;
        this.clock = clock;
        this.region = region;
    }

    public SmsProvider provider() {
        return provider;
    }

    public Sent send(UUID tenant, SendRequest r) {
        return send(tenant, r, null);
    }

    /** COM-027: {@code fallbackOf} is the WhatsApp message this SMS stands in for; its unique index allows one, before the BSP call. */
    public Sent send(UUID tenant, SendRequest r, UUID fallbackOf) {
        if (!"in".equalsIgnoreCase(region)) {
            throw refused(HttpStatus.UNPROCESSABLE_ENTITY, "SMS_DISABLED_FOR_REGION");
        }
        if (r.to() == null || !INDIAN_MOBILE.matcher(r.to()).matches()) {
            throw new InboxException(HttpStatus.BAD_REQUEST, "RECIPIENT_INVALID");
        }
        if (r.text() == null || r.text().isBlank() || r.text().length() > MAX_TEXT) {
            throw new InboxException(HttpStatus.BAD_REQUEST, "TEXT_INVALID");
        }
        UUID id = UUID.randomUUID();
        Prepared prepared;
        try {
            prepared = prepare(tenant, r, id, fallbackOf);
        } catch (DuplicateKeyException e) { // the same requestId raced in twice: the other call sends
            throw new InboxException(HttpStatus.CONFLICT, "DUPLICATE_REQUEST");
        }
        if (prepared.template() == null || "blocked".equals(prepared.sent().status())) {
            return prepared.sent();
        }
        Template t = prepared.template();
        var result = provider.send(new SmsProvider.SmsRequest(id, r.to(), t.entityId(), t.header(), t.dltTemplateId(), r.text()));
        String status = result instanceof Provider.Accepted ? "sent" : "failed";
        tx.executeWithoutResult(s -> {
            bind(tenant);
            Timestamp now = Timestamp.from(clock.instant());
            switch (result) {
                case Provider.Accepted a -> jdbc.update("UPDATE outbound_messages SET status = 'sent', provider_message_id = ?, attempts = 1, last_attempt_at = ?, "
                        + "updated_at = ? WHERE id = ? AND status = 'sending'", a.providerMessageId(), now, now, id);
                // no SMS retry lane: an OTP is useless minutes later, so the caller falls back or asks again
                case Provider.RateLimited x -> failed(id, x.code(), now);
                case Provider.Transient x -> failed(id, x.code(), now);
                case Provider.Rejected x -> failed(id, x.code(), now);
            }
        });
        metrics.counter("comms.sms.send", "outcome", status).increment();
        return new Sent(id, status);
    }

    private Prepared prepare(UUID tenant, SendRequest r, UUID id, UUID fallbackOf) {
        return tx.execute(s -> {
            bind(tenant);
            if (r.requestId() != null) { // same notification request again: answer with the first message, send nothing
                var again = jdbc.query("SELECT id, status FROM outbound_messages WHERE request_id = ? AND channel = 'sms'",
                        (rs, i) -> new Sent(rs.getObject(1, UUID.class), rs.getString(2)), r.requestId());
                if (!again.isEmpty()) {
                    return new Prepared(again.get(0), null);
                }
            }
            Template t = jdbc.query("SELECT id, key, language, dlt_entity_id, dlt_template_id, header, category, body FROM sms_templates "
                            + "WHERE status = 'active' ORDER BY tenant_id IS NULL, key, dlt_template_id", // the clinic's own registration first
                    (rs, i) -> new Template(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getString(6), rs.getString(7), rs.getString(8)))
                    .stream().filter(c -> DltTemplateMatcher.matches(c.body(), r.text())).findFirst()
                    .orElseThrow(() -> refused(HttpStatus.UNPROCESSABLE_ENTITY, "DLT_TEMPLATE_MISMATCH"));
            SendGate.Category cat = switch (t.category()) {
                case "otp" -> SendGate.Category.AUTHENTICATION;
                case "promotional" -> SendGate.Category.MARKETING;
                default -> SendGate.Category.UTILITY;
            };
            var decision = gate.check(new SendGate.Request(tenant, r.patientRef(), id, cat, false, null, null));
            String status = decision.allowed() ? "sending" : "blocked";
            Timestamp now = Timestamp.from(clock.instant());
            jdbc.update("""
                    INSERT INTO outbound_messages (id, tenant_id, request_id, channel, patient_ref, to_address_enc, to_hash, template_id, template_name,
                      language, status, error_code, fallback_of, created_at, updated_at)
                    VALUES (?,?,?, 'sms', ?,?,?,?,?, ?,?,?,?,?,?)""",
                    id, tenant, r.requestId(), r.patientRef(), crypto.encrypt(r.to()), crypto.hash(r.to()), t.id(), t.key(), t.language(),
                    status, decision.reason(), fallbackOf, now, now);
            return new Prepared(new Sent(id, status), t);
        });
    }

    /** Persist-then-ack: one encrypted row per report, a repeated report stores nothing new. The tenant is unknown until processed. */
    public void storeDlrs(List<SmsProvider.Dlr> dlrs) {
        tx.executeWithoutResult(s -> {
            bind(new UUID(0, 0));
            for (SmsProvider.Dlr d : dlrs) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", d.providerMessageId());
                m.put("status", d.status());
                m.put("errorCode", d.errorCode());
                jdbc.update("INSERT INTO inbound_events (source, dedupe_key, field, body_enc) VALUES (?,?, 'sms_dlr', ?) ON CONFLICT (dedupe_key) DO NOTHING",
                        "sms_" + provider.name(), "sms:" + provider.name() + ":" + d.providerMessageId() + ":" + d.status(),
                        crypto.encrypt(json.writeValueAsString(m)));
            }
        });
    }

    @Scheduled(fixedDelayString = "${comms.sms.dlr-tick-ms:2000}")
    public void scheduledDrain() {
        try {
            drainDlrs();
        } catch (RuntimeException e) { // DB or Valkey down: the reports stay pending and the next tick retries
            metrics.counter("comms.sms.dlr", "outcome", "drain_error").increment();
        }
    }

    /** @return reports applied (the message row moved forward) */
    public int drainDlrs() {
        return tx.execute(s -> {
            bind(new UUID(0, 0)); // a pooled connection may hold '' from an earlier transaction, which the RLS ::uuid cast rejects
            var rows = jdbc.query("SELECT id, body_enc FROM inbound_events WHERE status = 'pending' AND field = 'sms_dlr' ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED",
                    (rs, i) -> new Object[] {rs.getLong(1), rs.getBytes(2)});
            int applied = 0;
            for (Object[] row : rows) {
                long eventId = (long) row[0];
                JsonNode d;
                try {
                    d = json.readTree(crypto.decrypt((byte[]) row[1]).getBytes(StandardCharsets.UTF_8));
                } catch (RuntimeException e) {
                    jdbc.update("UPDATE inbound_events SET status = 'dead', last_error_code = 'UNREADABLE', processed_at = now() WHERE id = ?", eventId);
                    continue;
                }
                String providerId = d.path("id").asString(), status = d.path("status").asString(), err = d.path("errorCode").asString(null);
                jdbc.queryForObject("SELECT set_config('app.dlr_lookup', 'on', true)", String.class);
                var msg = jdbc.query("SELECT id, tenant_id, request_id FROM outbound_messages WHERE provider_message_id = ? AND channel = 'sms'",
                        (rs, i) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)}, providerId);
                jdbc.queryForObject("SELECT set_config('app.dlr_lookup', 'off', true)", String.class);
                if (msg.isEmpty()) {
                    jdbc.update("UPDATE inbound_events SET attempts = attempts + 1, last_error_code = 'UNKNOWN_MESSAGE', "
                            + "status = CASE WHEN attempts + 1 >= ? THEN 'ignored' ELSE 'pending' END WHERE id = ?", DLR_MAX_ATTEMPTS, eventId);
                    continue;
                }
                UUID messageId = msg.get(0)[0], tenant = msg.get(0)[1], requestId = msg.get(0)[2];
                bind(tenant);
                boolean moved = statuses.apply(providerId, status);
                if (moved && "failed".equals(status)) {
                    jdbc.update("UPDATE outbound_messages SET error_code = ? WHERE id = ?", err, messageId);
                }
                jdbc.update("UPDATE inbound_events SET tenant_id = ?, status = 'done', attempts = attempts + 1, processed_at = now() WHERE id = ?", tenant, eventId);
                if (moved) {
                    applied++;
                    Instant at = clock.instant();
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("tenantId", tenant.toString());
                    data.put("messageId", messageId.toString());
                    if (requestId != null) {
                        data.put("requestId", requestId.toString());
                    }
                    data.put("channel", "sms");
                    data.put("status", status);
                    data.put("at", at.toString());
                    data.put("version", at.toEpochMilli());
                    events.publish(messageId + ":" + status, "comms.message.status_changed.v1", tenant, data);
                }
            }
            return applied;
        });
    }

    private void failed(UUID id, String code, Timestamp now) {
        jdbc.update("UPDATE outbound_messages SET status = 'failed', error_code = ?, attempts = 1, last_attempt_at = ?, updated_at = ? WHERE id = ? AND status = 'sending'",
                code, now, now, id);
    }

    private InboxException refused(HttpStatus status, String code) {
        metrics.counter("comms_send_refused_total", "reason", code).increment();
        return new InboxException(status, code);
    }

    private void bind(UUID tenant) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
    }
}
