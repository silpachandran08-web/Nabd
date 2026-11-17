package com.nabd.comms.sms;

import com.nabd.comms.inbox.FieldCrypto;
import com.nabd.comms.inbox.InboxException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * COM-027 (T-309, FRD NFR-12): a WhatsApp message that does not arrive goes out once more by SMS, never back to WhatsApp.
 * <ul>
 *   <li>OTP lane: no 'delivered' 30 s after it was sent (or queued) -&gt; SMS. Other lanes: 'failed', or no delivery within 5 min -&gt; SMS.</li>
 *   <li>BULK never falls back (cost and DND). A delivered / read / blocked / cancelled / expired message never falls back.</li>
 *   <li>A number Meta reports as not on WhatsApp (131026) is marked in channel_reachability; its next due message goes SMS-first and
 *       the WhatsApp row is cancelled.</li>
 *   <li>The SMS is the clinic's DLT template with the same key as the WhatsApp template, filled with the same parameters; when a
 *       parameter is a secure link, with the link only. No template = no fallback.</li>
 *   <li>One SMS per original: the SMS row carries fallback_of (unique) and the request id (unique per channel), so a second timer,
 *       a second instance or the desk's 'Send as SMS' cannot send twice. After the SMS the original is marked and never looked at again.</li>
 * </ul>
 * The SMS itself goes through {@link SmsService} (India only, DLT match, consent gate at send time). Numbers and texts are never logged.
 */
public class SmsFallback {

    public record Sent(UUID messageId, String status) {}

    static final Duration OTP_WAIT = Duration.ofSeconds(30);
    static final Duration OTHER_WAIT = Duration.ofMinutes(5);
    /** Older rows are history (the feature was off, or the message no longer matters): never fallen back. */
    static final Duration HORIZON = Duration.ofHours(1);
    /** A 'not on WhatsApp' mark is trusted this long; then WhatsApp is tried again. */
    static final Duration REACHABILITY_TTL = Duration.ofDays(30);
    static final Set<String> NOT_ON_WHATSAPP = Set.of("131026");
    static final int BATCH = 200;
    private static final Pattern LINK = Pattern.compile("https://\\S+");
    private static final String SEP = "\u001f"; // params_enc: unit-separator joined (ReplyService, backfill)

    private record Original(UUID id, UUID tenant, UUID requestId, UUID patientRef, UUID threadId, String status, String priority, byte[] toEnc,
                            byte[] toHash, String templateName, String language, byte[] paramsEnc, String errorCode, Timestamp notAfter) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final FieldCrypto crypto;
    private final SmsService sms;
    private final MeterRegistry metrics;
    private final Clock clock;

    public SmsFallback(JdbcTemplate jdbc, TransactionTemplate tx, FieldCrypto crypto, SmsService sms, MeterRegistry metrics, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.crypto = crypto;
        this.sms = sms;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${comms.sms.fallback-tick-ms:5000}")
    public void scheduledTick() {
        try {
            tick();
        } catch (RuntimeException e) { // DB down: nothing was marked, the next tick sees the same rows
            metrics.counter("comms.sms.fallback", "outcome", "tick_error").increment();
        }
    }

    /** @return SMS sent (or handed to the BSP) in this pass */
    public int tick() {
        Instant now = clock.instant();
        List<UUID[]> due = tx.execute(s -> {
            jdbc.queryForObject("SELECT set_config('app.fallback_scan', 'on', true)", String.class);
            bind(new UUID(0, 0)); // a pooled connection may hold '' from an earlier transaction, which the RLS ::uuid cast rejects
            return jdbc.query("""
                    SELECT m.tenant_id, m.id FROM outbound_messages m
                    WHERE m.channel = 'whatsapp' AND m.fallback_decided_at IS NULL AND upper(m.priority) <> 'BULK' AND m.updated_at > ?
                      AND (m.status = 'failed'
                        OR (m.status IN ('queued','sending','sent') AND m.updated_at <= CASE WHEN upper(m.priority) = 'OTP' THEN ?::timestamptz ELSE ?::timestamptz END)
                        OR (m.status = 'queued' AND EXISTS (SELECT 1 FROM channel_reachability r WHERE r.phone_hash = m.to_hash
                              AND NOT r.whatsapp_reachable AND r.last_checked_at > ?)))
                    ORDER BY m.updated_at LIMIT ?""",
                    (rs, i) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)},
                    ts(now.minus(HORIZON)), ts(now.minus(OTP_WAIT)), ts(now.minus(OTHER_WAIT)), ts(now.minus(REACHABILITY_TTL)), BATCH);
        });
        int sent = 0;
        for (UUID[] d : due) {
            if (fallBack(d[0], d[1])) {
                sent++;
            }
        }
        return sent;
    }

    /** One due message under its own tenant. Re-reads it first: a 'delivered' that arrived since the scan stops the SMS. */
    boolean fallBack(UUID tenant, UUID id) {
        Instant now = clock.instant();
        Original o = load(tenant, id);
        if (o == null || o.notAfter() != null && o.notAfter().toInstant().isBefore(now)) {
            decide(tenant, id, "expired");
            return false;
        }
        if (!Set.of("queued", "sending", "sent", "failed").contains(o.status())) {
            return false; // delivered (or settled) in the meantime: no SMS; the scan no longer selects it
        }
        if (o.errorCode() != null && NOT_ON_WHATSAPP.contains(o.errorCode())) {
            markNotOnWhatsApp(o.toHash(), o.errorCode());
        }
        if (prior(o) != null) { // sent before the mark was written (crash, another instance, the desk)
            decide(tenant, id, "already_sent");
            return false;
        }
        try {
            Sent s = send(o);
            if (s == null) {
                return false;
            }
            count("sms_" + s.status());
            return "sent".equals(s.status());
        } catch (InboxException e) { // DLT mismatch, region, recipient: retrying cannot help
            decide(tenant, id, e.code());
            return false;
        }
    }

    /**
     * Reception's 'Send as SMS' on a message that was not delivered (COM-027.3): same template, same secure link, same one-SMS rule.
     * Core has checked inbox:reply; consent and region are checked by {@link SmsService} at send time.
     */
    public Sent sendAsSms(UUID tenant, UUID messageId, UUID staff) {
        Original o = load(tenant, messageId);
        if (o == null || o.threadId() == null) {
            throw new InboxException(HttpStatus.NOT_FOUND, "MESSAGE_NOT_FOUND");
        }
        if (!Set.of("failed", "expired").contains(o.status())) {
            throw new InboxException(HttpStatus.CONFLICT, "MESSAGE_NOT_FAILED");
        }
        if (prior(o) != null) {
            throw new InboxException(HttpStatus.CONFLICT, "ALREADY_SENT_BY_SMS");
        }
        Sent s = send(o);
        if (s == null) {
            throw new InboxException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_SMS_TEMPLATE");
        }
        tx.executeWithoutResult(st -> {
            bind(tenant);
            jdbc.update("INSERT INTO inbox_audit (id, tenant_id, thread_id, staff_ref, action, detail, at) VALUES (?,?,?,?, 'send_as_sms', ?, ?)",
                    UUID.randomUUID(), tenant, o.threadId(), staff, "message=" + messageId + " sms=" + s.messageId(), ts(clock.instant()));
        });
        count("desk_" + s.status());
        return s;
    }

    /** True when this number is known not to be on WhatsApp (mark younger than 30 days): send by SMS first. */
    public boolean smsFirst(byte[] phoneHash) {
        Instant since = clock.instant().minus(REACHABILITY_TTL);
        return Boolean.TRUE.equals(tx.execute(s -> {
            bind(new UUID(0, 0));
            return jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM channel_reachability WHERE phone_hash = ? AND NOT whatsapp_reachable AND last_checked_at > ?)",
                    Boolean.class, phoneHash, ts(since));
        }));
    }

    /** Renders the SMS, sends it linked to the original, then marks the original (and cancels it when it never left). null = no template. */
    private Sent send(Original o) {
        String text = render(o);
        if (text == null) {
            decide(o.tenant(), o.id(), "no_sms_template");
            return null;
        }
        SmsService.Sent s;
        try {
            s = sms.send(o.tenant(), new SmsService.SendRequest(crypto.decrypt(o.toEnc()), text, o.patientRef(), o.requestId()), o.id());
        } catch (InboxException e) {
            if (!"DUPLICATE_REQUEST".equals(e.code())) {
                throw e;
            }
            s = existing(o); // another instance (or the desk) sent it a moment ago
        }
        SmsService.Sent done = s;
        tx.executeWithoutResult(st -> {
            bind(o.tenant());
            Timestamp now = ts(clock.instant());
            jdbc.update("UPDATE outbound_messages SET fallback_of = coalesce(fallback_of, ?), thread_id = coalesce(thread_id, ?) WHERE id = ? AND channel = 'sms'",
                    o.id(), o.threadId(), done.messageId());
            jdbc.update("UPDATE outbound_messages SET fallback_decided_at = coalesce(fallback_decided_at, ?) WHERE id = ?", now, o.id());
            if ("sent".equals(done.status())) { // the WhatsApp copy must never leave after the SMS did
                jdbc.update("UPDATE outbound_messages SET status = 'cancelled', error_code = 'sent-by-sms', updated_at = ? WHERE id = ? AND status = 'queued'", now, o.id());
            }
        });
        return new Sent(done.messageId(), done.status());
    }

    private SmsService.Sent existing(Original o) {
        SmsService.Sent s = prior(o);
        if (s == null) {
            throw new InboxException(HttpStatus.CONFLICT, "DUPLICATE_REQUEST");
        }
        return s;
    }

    /** The SMS already standing in for this message: linked to it, or of the same notification request. */
    private SmsService.Sent prior(Original o) {
        return tx.execute(st -> {
            bind(o.tenant());
            return jdbc.query("SELECT id, status FROM outbound_messages WHERE channel = 'sms' AND (fallback_of = ? OR request_id = ?) LIMIT 1",
                    (rs, i) -> new SmsService.Sent(rs.getObject(1, UUID.class), rs.getString(2)), o.id(), o.requestId()).stream().findFirst().orElse(null);
        });
    }

    /** The clinic's (else the shared) active SMS template with the WhatsApp template's key, the message's language first, then English. */
    private String render(Original o) {
        List<String> params = o.paramsEnc() == null ? List.of() : Arrays.asList(crypto.decrypt(o.paramsEnc()).split(SEP, -1));
        List<String> body = tx.execute(s -> {
            bind(o.tenant());
            return jdbc.queryForList("SELECT body FROM sms_templates WHERE key = ? AND status = 'active' AND category <> 'promotional' "
                    + "ORDER BY language = ? DESC, language = 'en' DESC, tenant_id IS NULL, dlt_template_id", String.class, o.templateName(), o.language());
        });
        return body.isEmpty() ? null : fill(body.get(0), params);
    }

    /** {#var#} in order from the params; a message that carries a secure link falls back with the link only. null = too few values. */
    static String fill(String registered, List<String> params) {
        List<String> links = params.stream().filter(p -> LINK.matcher(p).matches()).toList();
        List<String> values = links.isEmpty() ? params : links;
        String[] literal = registered.split(Pattern.quote(DltTemplateMatcher.VAR), -1);
        if (literal.length - 1 > values.size()) {
            return null;
        }
        StringBuilder out = new StringBuilder(literal[0]);
        for (int i = 1; i < literal.length; i++) {
            out.append(values.get(i - 1)).append(literal[i]);
        }
        return out.toString();
    }

    private Original load(UUID tenant, UUID id) {
        return tx.execute(s -> {
            bind(tenant);
            return jdbc.query("""
                    SELECT id, tenant_id, request_id, patient_ref, thread_id, status, priority, to_address_enc, to_hash, template_name, language,
                      params_enc, error_code, not_after FROM outbound_messages WHERE id = ? AND channel = 'whatsapp'""",
                    (rs, i) -> new Original(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                            rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7), rs.getBytes(8), rs.getBytes(9), rs.getString(10), rs.getString(11),
                            rs.getBytes(12), rs.getString(13), rs.getTimestamp(14)), id).stream().findFirst().orElse(null);
        });
    }

    private void markNotOnWhatsApp(byte[] phoneHash, String reason) {
        tx.executeWithoutResult(s -> {
            bind(new UUID(0, 0));
            jdbc.update("""
                    INSERT INTO channel_reachability (phone_hash, whatsapp_reachable, last_checked_at, reason_code) VALUES (?, false, ?, ?)
                    ON CONFLICT (phone_hash) DO UPDATE SET whatsapp_reachable = false, last_checked_at = excluded.last_checked_at, reason_code = excluded.reason_code""",
                    phoneHash, ts(clock.instant()), reason);
        });
        count("marked_not_on_whatsapp");
    }

    /** The original is settled without an SMS (reason in the metric only: the row keeps its own status and error). */
    private void decide(UUID tenant, UUID id, String reason) {
        tx.executeWithoutResult(s -> {
            bind(tenant);
            jdbc.update("UPDATE outbound_messages SET fallback_decided_at = ? WHERE id = ? AND fallback_decided_at IS NULL", ts(clock.instant()), id);
        });
        count(reason);
    }

    private void count(String outcome) {
        metrics.counter("comms.sms.fallback", "outcome", outcome).increment();
    }

    private void bind(UUID tenant) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
