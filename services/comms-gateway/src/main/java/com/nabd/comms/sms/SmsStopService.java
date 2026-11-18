package com.nabd.comms.sms;

import com.nabd.comms.inbox.FieldCrypto;
import com.nabd.comms.inbox.InboxException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * COM-028.1: works off the stored inbound SMS. Core's COM-002 matcher classifies the text and withdraws the marketing consent through
 * Core's consent API (POST /internal/v1/consents:sms-keyword, comms service token): a phone-level suppression for every clinic plus the
 * consent rows in the clinic of the most recent SMS to the number. The gateway then marks the number opted out for promotional SMS and,
 * for a new opt-out, confirms by SMS with the 'optout_confirm' DLT template. Core unreachable: the row goes back to pending and the
 * 2 s tick retries, dead after MAX_ATTEMPTS (visible). A text longer than any keyword never leaves the gateway. Nothing here is logged.
 */
public class SmsStopService {

    static final int MAX_KEYWORD = 64;
    static final int MAX_ATTEMPTS = 100;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final FieldCrypto crypto;
    private final SmsService sms;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final RestClient core;
    private final String token;

    public SmsStopService(JdbcTemplate jdbc, TransactionTemplate tx, FieldCrypto crypto, SmsService sms, ObjectMapper json, MeterRegistry metrics,
                          String coreBaseUrl, String coreToken) {
        var f = new JdkClientHttpRequestFactory();
        f.setReadTimeout(Duration.ofSeconds(5));
        this.jdbc = jdbc;
        this.tx = tx;
        this.crypto = crypto;
        this.sms = sms;
        this.json = json;
        this.metrics = metrics;
        this.core = RestClient.builder().baseUrl(coreBaseUrl).requestFactory(f).build();
        this.token = coreToken;
    }

    @Scheduled(fixedDelayString = "${comms.sms.mo-tick-ms:2000}")
    public void scheduledDrain() {
        try {
            drain();
        } catch (RuntimeException e) { // DB down: the rows stay pending and the next tick retries
            metrics.counter("comms.sms.mo", "outcome", "drain_error").increment();
        }
    }

    /** @return inbound SMS that opted a number out */
    public int drain() {
        List<Object[]> claimed = tx.execute(s -> {
            bind(new UUID(0, 0));
            return jdbc.query("""
                    UPDATE inbound_events SET status = 'processing', locked_until = now() + interval '2 minutes', attempts = attempts + 1
                    WHERE id IN (SELECT id FROM inbound_events WHERE field = 'sms_mo' AND (status = 'pending' OR (status = 'processing' AND locked_until < now()))
                                 ORDER BY id LIMIT 50 FOR UPDATE SKIP LOCKED)
                    RETURNING id, body_enc, attempts""", (rs, i) -> new Object[] {rs.getLong(1), rs.getBytes(2), rs.getInt(3)});
        });
        int stops = 0;
        for (Object[] row : claimed) {
            long id = (long) row[0];
            String outcome;
            String error = null;
            try {
                outcome = process(id, (byte[]) row[1]);
            } catch (RuntimeException e) { // Core unreachable, 5xx or an unreadable answer
                outcome = (int) row[2] >= MAX_ATTEMPTS ? "dead" : "pending";
                error = "CORE_UNAVAILABLE";
            }
            stops += "done".equals(outcome) ? 1 : 0;
            String status = outcome, code = error;
            tx.executeWithoutResult(s -> {
                bind(new UUID(0, 0));
                jdbc.update("UPDATE inbound_events SET status = ?, last_error_code = ?, locked_until = NULL, "
                        + "processed_at = CASE WHEN ? = 'pending' THEN NULL ELSE now() END WHERE id = ?", status, code, status, id);
            });
            metrics.counter("comms.sms.mo", "outcome", outcome).increment();
        }
        return stops;
    }

    /** done = opted out; ignored = not a STOP; dead = unreadable. Throws when Core gave no answer (retried). */
    private String process(long eventId, byte[] enc) {
        JsonNode m;
        try {
            m = json.readTree(crypto.decrypt(enc).getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return "dead";
        }
        String from = m.path("from").asString(""), text = m.path("text").asString("");
        if (text.isBlank() || text.length() > MAX_KEYWORD || !from.startsWith("+")) {
            return "ignored";
        }
        UUID tenant = lastSmsTenant(from);
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("phone", from.substring(1));
        req.put("text", text);
        req.put("tenantId", tenant);
        req.put("inboundRef", eventId);
        JsonNode a = core.post().uri("/internal/v1/consents:sms-keyword").header("X-Internal-Token", token).contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(req)).retrieve().body(JsonNode.class);
        if (a == null || !a.path("optedOut").isBoolean()) {
            throw new IllegalStateException("no answer");
        }
        if (!a.path("optedOut").asBoolean()) {
            return "ignored";
        }
        jdbc.update("INSERT INTO sms_numbers (phone_hash, marketing_opt_out_at) VALUES (?, now()) ON CONFLICT (phone_hash) DO UPDATE "
                + "SET marketing_opt_out_at = COALESCE(sms_numbers.marketing_opt_out_at, EXCLUDED.marketing_opt_out_at)", crypto.hash(from));
        if (a.path("confirm").asBoolean(false) && tenant != null) { // one confirmation per opt-out, not per repeated STOP
            confirm(tenant, from, eventId);
        }
        return "done";
    }

    /** The clinic of the most recent SMS to the number: the STOP's context. Read through the dlr_lookup policy (V26, SELECT on sms rows only). */
    private UUID lastSmsTenant(String to) {
        return tx.execute(s -> {
            bind(new UUID(0, 0));
            jdbc.queryForObject("SELECT set_config('app.dlr_lookup', 'on', true)", String.class);
            var t = jdbc.query("SELECT tenant_id FROM outbound_messages WHERE channel = 'sms' AND to_hash = ? ORDER BY created_at DESC LIMIT 1",
                    (rs, i) -> rs.getObject(1, UUID.class), crypto.hash(to));
            jdbc.queryForObject("SELECT set_config('app.dlr_lookup', 'off', true)", String.class);
            return t.isEmpty() ? null : t.get(0);
        });
    }

    /** Best effort, like Core's WhatsApp confirmation: the clinic's own registration first, else the Nabd-shared one; no template = no SMS. */
    private void confirm(UUID tenant, String to, long eventId) {
        String body = tx.execute(s -> {
            bind(tenant);
            return jdbc.query("SELECT body FROM sms_templates WHERE key = 'optout_confirm' AND status = 'active' ORDER BY tenant_id IS NULL LIMIT 1",
                    (rs, i) -> rs.getString(1)).stream().findFirst().orElse(null);
        });
        if (body == null || body.contains("{#var#}")) {
            metrics.counter("comms.sms.mo", "outcome", "no_confirm_template").increment();
            return;
        }
        try {
            sms.send(tenant, new SmsService.SendRequest(to, body, null, UUID.nameUUIDFromBytes(("sms-stop:" + eventId).getBytes(StandardCharsets.UTF_8))));
        } catch (InboxException e) {
            metrics.counter("comms.sms.mo", "outcome", "confirm_refused").increment();
        }
    }

    private void bind(UUID tenant) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
    }
}
