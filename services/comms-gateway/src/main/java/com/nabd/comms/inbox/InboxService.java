package com.nabd.comms.inbox;

import com.nabd.comms.events.StreamEventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * COM-030: conversation threads. One thread per tenant + sender number + patient phone; unread count, waiting time and the
 * 24 h customer-service window (open until last_inbound_at + 24 h). Message text is encrypted; ids and counts only in logs/events.
 */
@Service
@ConditionalOnProperty("comms.field-key")
public class InboxService {

    public static final Duration CLOSING_WITHIN = Duration.ofHours(2); // shortcut: fixed 'window closing' threshold, make it a clinic setting when asked
    static final int MAX_LIMIT = 100;

    public record Inbound(String wamid, String kind, String text, String buttonPayload, UUID mediaRef, String contextWamid, Instant receivedAt) {}

    public record Receipt(UUID threadId, UUID inboundId, boolean duplicate) {}

    public record Window(String state, Instant closesAt) {}

    public record ThreadItem(UUID id, UUID patientRef, List<UUID> familyPatientRefs, String displayName, String lastMessagePreview, int unread,
                             long waitingMinutes, Window window, String mode, UUID assignedTo, boolean optedOut) {}

    public record ThreadPage(List<ThreadItem> items, String nextCursor) {}

    public record MessageItem(UUID id, String direction, String author, String text, String templateName, UUID mediaRef, String status, Instant at) {}

    public record MessagePage(List<MessageItem> items, String nextCursor) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final FieldCrypto crypto;
    private final StreamEventPublisher events;
    private final ObjectProvider<PatientDirectory> directory;
    private final Clock clock;

    public InboxService(JdbcTemplate jdbc, TransactionTemplate tx, FieldCrypto crypto, StreamEventPublisher events,
                 ObjectProvider<PatientDirectory> directory, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.crypto = crypto;
        this.events = events;
        this.directory = directory;
        this.clock = clock;
    }

    // ---- write side ----

    /** Stores one routed inbound message: thread upsert, link to patient/family, unread + waiting, event. Idempotent on wamid. */
    public Receipt recordInbound(UUID tenantId, UUID senderAccountId, String phone, Inbound m) {
        String normalized = phone.replaceAll("[^0-9+]", "");
        byte[] hash = crypto.hash(normalized);
        boolean linked = tx.execute(x -> {
            bind(tenantId);
            return !jdbc.query("SELECT 1 FROM inbox_threads WHERE sender_account_id = ? AND phone_hash = ? AND (patient_ref IS NOT NULL OR cardinality(family_patient_refs) > 0)",
                    (rs, i) -> 1, senderAccountId, hash).isEmpty();
        });
        List<UUID> matches = List.of();
        PatientDirectory dir = directory.getIfAvailable();
        if (!linked && dir != null) { // thread unknown or unlinked: ask Core, outside any transaction
            matches = dir.byPhone(tenantId, normalized);
        }
        List<UUID> found = matches;
        return tx.execute(s -> {
            bind(tenantId);
            UUID patient = found.size() == 1 ? found.get(0) : null;
            UUID[] family = found.size() > 1 ? found.toArray(new UUID[0]) : new UUID[0];
            UUID threadId = jdbc.execute((java.sql.Connection c) -> {
                try (var ps = c.prepareStatement("""
                        INSERT INTO inbox_threads (id, tenant_id, sender_account_id, phone_hash, phone_enc, patient_ref, family_patient_refs)
                        VALUES (?,?,?,?,?,?,?)
                        ON CONFLICT (tenant_id, sender_account_id, phone_hash) DO UPDATE SET
                          patient_ref = COALESCE(inbox_threads.patient_ref, EXCLUDED.patient_ref),
                          family_patient_refs = CASE WHEN cardinality(inbox_threads.family_patient_refs) = 0 THEN EXCLUDED.family_patient_refs
                                                     ELSE inbox_threads.family_patient_refs END
                        RETURNING id""")) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, tenantId);
                    ps.setObject(3, senderAccountId);
                    ps.setBytes(4, hash);
                    ps.setBytes(5, crypto.encrypt(normalized));
                    ps.setObject(6, patient);
                    ps.setArray(7, c.createArrayOf("uuid", family));
                    try (var rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getObject(1, UUID.class);
                    }
                }
            });
            UUID id = UUID.randomUUID();
            List<Long> seq = jdbc.query("""
                    INSERT INTO inbound_messages (id, tenant_id, thread_id, wamid, kind, text_enc, button_payload, media_ref, context_wamid, received_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT (wamid) DO NOTHING RETURNING seq""",
                    (rs, i) -> rs.getLong(1), id, tenantId, threadId, m.wamid(), m.kind(), m.text() == null ? null : crypto.encrypt(m.text()),
                    m.buttonPayload(), m.mediaRef(), m.contextWamid(), Timestamp.from(m.receivedAt()));
            if (seq.isEmpty()) {
                return new Receipt(threadId, null, true); // same wamid again: no second unread
            }
            jdbc.update("""
                    UPDATE inbox_threads SET last_inbound_at = GREATEST(coalesce(last_inbound_at, '-infinity'), ?), unread_count = unread_count + 1,
                      waiting_since = COALESCE(waiting_since, ?), state = 'open' WHERE id = ?""",
                    Timestamp.from(m.receivedAt()), Timestamp.from(m.receivedAt()), threadId);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("tenantId", tenantId.toString());
            d.put("inboundId", seq.get(0));
            d.put("threadId", threadId.toString());
            d.put("kind", eventKind(m.kind()));
            if (m.buttonPayload() != null && m.buttonPayload().matches("^[A-Za-z0-9_.: -]{1,64}$")) {
                d.put("buttonPayload", m.buttonPayload());
            }
            d.put("receivedAt", m.receivedAt().toString());
            d.put("version", seq.get(0));
            events.publish("inbound-" + seq.get(0), "comms.inbound.received.v1", tenantId, d);
            return new Receipt(threadId, id, false);
        });
    }

    /** A staff or bot reply went out: the patient is no longer waiting. */
    public void markReplied(UUID tenantId, UUID threadId, Instant at) {
        tx.executeWithoutResult(s -> {
            bind(tenantId);
            jdbc.update("UPDATE inbox_threads SET last_outbound_at = GREATEST(coalesce(last_outbound_at, '-infinity'), ?), waiting_since = NULL WHERE id = ?",
                    Timestamp.from(at), threadId);
        });
    }

    /** Free text is allowed only while the window is open; afterwards only approved templates (409 SESSION_WINDOW_CLOSED). */
    public void requireFreeTextAllowed(UUID tenantId, UUID threadId) {
        Window w = tx.execute(s -> {
            bind(tenantId);
            return jdbc.query("SELECT window_expires_at FROM inbox_threads WHERE id = ?", (rs, i) -> window(rs.getTimestamp(1), clock.instant()), threadId)
                    .stream().findFirst().orElseThrow(() -> new InboxException(HttpStatus.NOT_FOUND, "THREAD_NOT_FOUND"));
        });
        if (!"open".equals(w.state())) {
            throw new InboxException(HttpStatus.CONFLICT, "SESSION_WINDOW_CLOSED");
        }
    }

    /** Reception picks one of the family members the phone matched. */
    public void pickPatient(UUID tenantId, UUID threadId, UUID patientRef) {
        tx.executeWithoutResult(s -> {
            bind(tenantId);
            List<UUID[]> fam = jdbc.query("SELECT family_patient_refs FROM inbox_threads WHERE id = ?", (rs, i) -> uuids(rs.getArray(1)), threadId);
            if (fam.isEmpty()) {
                throw new InboxException(HttpStatus.NOT_FOUND, "THREAD_NOT_FOUND");
            }
            if (!List.of(fam.get(0)).contains(patientRef)) {
                throw new InboxException(HttpStatus.BAD_REQUEST, "PATIENT_NOT_IN_FAMILY");
            }
            jdbc.update("UPDATE inbox_threads SET patient_ref = ? WHERE id = ?", patientRef, threadId);
        });
    }

    /** Marks everything up to (and including) the given message read; never raises the count. */
    public void markRead(UUID tenantId, UUID threadId, UUID upToMessageId) {
        tx.executeWithoutResult(s -> {
            bind(tenantId);
            List<Timestamp> at = jdbc.query("SELECT received_at FROM inbound_messages WHERE id = ? AND thread_id = ?", (rs, i) -> rs.getTimestamp(1), upToMessageId, threadId);
            if (at.isEmpty()) {
                throw new InboxException(HttpStatus.NOT_FOUND, "MESSAGE_NOT_FOUND");
            }
            jdbc.update("""
                    UPDATE inbox_threads SET unread_count = LEAST(unread_count,
                      (SELECT count(*) FROM inbound_messages WHERE thread_id = ? AND received_at > ?)) WHERE id = ?""", threadId, at.get(0), threadId);
        });
    }

    // ---- read side ----

    /** filter: all | unread | needs_reply | bot | closing. Oldest-waiting first; keyset cursor on (waiting_since, id). */
    public ThreadPage threads(UUID tenantId, String filter, String cursor, int limit) {
        int lim = Math.max(1, Math.min(limit, MAX_LIMIT));
        Instant now = clock.instant();
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("t.state = 'open'");
        switch (filter == null ? "all" : filter) {
            case "all" -> { }
            case "unread" -> where.append(" AND t.unread_count > 0");
            case "needs_reply" -> where.append(" AND t.last_inbound_at IS NOT NULL AND (t.last_outbound_at IS NULL OR t.last_outbound_at < t.last_inbound_at)");
            case "bot" -> where.append(" AND t.mode = 'bot'");
            case "closing" -> {
                where.append(" AND t.window_expires_at > ? AND t.window_expires_at <= ?");
                args.add(Timestamp.from(now));
                args.add(Timestamp.from(now.plus(CLOSING_WITHIN)));
            }
            default -> throw new InboxException(HttpStatus.BAD_REQUEST, "INVALID_FILTER");
        }
        if (cursor != null && !cursor.isBlank()) {
            String[] c = decode(cursor);
            where.append(" AND (coalesce(t.waiting_since, 'infinity'::timestamptz), t.id) > (?::timestamptz, ?::uuid)");
            args.add(c[0]);
            args.add(c[1]);
        }
        args.add(lim + 1);
        String sql = """
                SELECT s.id, s.patient_ref, s.family_patient_refs, s.phone_enc, s.unread_count, s.waiting_since, s.window_expires_at, s.mode,
                       s.assigned_staff_ref, s.opted_out, s.sk, p.kind, p.text_enc
                FROM (SELECT t.*, coalesce(t.waiting_since, 'infinity'::timestamptz)::text AS sk FROM inbox_threads t WHERE %s
                      ORDER BY coalesce(t.waiting_since, 'infinity'::timestamptz), t.id LIMIT ?) s
                LEFT JOIN LATERAL (SELECT kind, text_enc FROM inbound_messages m WHERE m.thread_id = s.id ORDER BY received_at DESC LIMIT 1) p ON true
                ORDER BY coalesce(s.waiting_since, 'infinity'::timestamptz), s.id""".formatted(where);
        record Raw(ThreadItem item, String sk) {}
        List<Raw> rows = tx.execute(s -> {
            bind(tenantId);
            return jdbc.query(sql, (rs, i) -> {
                Timestamp waiting = rs.getTimestamp(6);
                int unread = rs.getInt(5);
                String preview = rs.getBytes(13) != null ? truncate(crypto.decrypt(rs.getBytes(13))) : rs.getString(12) == null ? "" : "[" + rs.getString(12) + "]";
                UUID[] fam = uuids(rs.getArray(3));
                return new Raw(new ThreadItem(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), List.of(fam), mask(crypto.decrypt(rs.getBytes(4))), preview, unread,
                        waiting == null ? 0 : Math.max(0, Duration.between(waiting.toInstant(), now).toMinutes()), window(rs.getTimestamp(7), now),
                        rs.getString(8), rs.getObject(9, UUID.class), rs.getBoolean(10)), rs.getString(11));
            }, args.toArray());
        });
        boolean more = rows.size() > lim;
        List<Raw> page = more ? rows.subList(0, lim) : rows;
        return new ThreadPage(page.stream().map(Raw::item).toList(), more ? encode(page.get(page.size() - 1).sk(), page.get(page.size() - 1).item().id()) : null);
    }

    /** Both directions, newest first; keyset cursor on (at, id). 404 for an unknown thread of this tenant. */
    public MessagePage messages(UUID tenantId, UUID threadId, String cursor, int limit) {
        int lim = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<Object> args = new ArrayList<>(List.of(threadId, threadId));
        String after = "";
        if (cursor != null && !cursor.isBlank()) {
            String[] c = decode(cursor);
            after = "WHERE (m.at, m.id) < (?::timestamptz, ?::uuid)";
            args.add(c[0]);
            args.add(c[1]);
        }
        args.add(lim + 1);
        String sql = """
                SELECT m.id, m.dir, m.kind, m.text_enc, m.template_name, m.media_ref, m.status, m.at, m.at::text FROM (
                  SELECT id, 'in' dir, kind, text_enc, NULL::text template_name, media_ref, NULL::text status, received_at at FROM inbound_messages WHERE thread_id = ?
                  UNION ALL
                  SELECT id, 'out', NULL, NULL, template_name, NULL::uuid, status, created_at FROM outbound_messages WHERE thread_id = ?) m
                %s ORDER BY m.at DESC, m.id DESC LIMIT ?""".formatted(after);
        record Raw(MessageItem item, String at) {}
        List<Raw> rows = tx.execute(s -> {
            bind(tenantId);
            if (jdbc.queryForObject("SELECT count(*) FROM inbox_threads WHERE id = ?", Integer.class, threadId) == 0) {
                throw new InboxException(HttpStatus.NOT_FOUND, "THREAD_NOT_FOUND");
            }
            return jdbc.query(sql, (rs, i) -> {
                boolean in = "in".equals(rs.getString(2));
                String text = rs.getBytes(4) == null ? null : crypto.decrypt(rs.getBytes(4));
                return new Raw(new MessageItem(rs.getObject(1, UUID.class), in ? "in" : "out", in ? "patient" : "template", text, rs.getString(5),
                        rs.getObject(6, UUID.class), rs.getString(7), rs.getTimestamp(8).toInstant()), rs.getString(9));
            }, args.toArray());
        });
        boolean more = rows.size() > lim;
        List<Raw> page = more ? rows.subList(0, lim) : rows;
        return new MessagePage(page.stream().map(Raw::item).toList(), more ? encode(page.get(page.size() - 1).at(), page.get(page.size() - 1).item().id()) : null);
    }

    // ---- helpers ----

    static Window window(Timestamp expires, Instant now) {
        if (expires == null) {
            return new Window("closed", null);
        }
        return new Window(expires.toInstant().isAfter(now) ? "open" : "closed", expires.toInstant());
    }

    private void bind(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
        jdbc.execute("SET LOCAL TIME ZONE 'UTC'"); // cursors carry timestamps as text
    }

    private static UUID[] uuids(Array a) throws java.sql.SQLException {
        return a == null ? new UUID[0] : (UUID[]) a.getArray();
    }

    private static String eventKind(String kind) {
        return switch (kind) {
            case "text", "button", "interactive", "location" -> kind;
            case "image", "audio", "document" -> "media";
            default -> "other";
        };
    }

    private static String mask(String phone) {
        return phone.length() <= 4 ? phone : "•".repeat(phone.length() - 4) + phone.substring(phone.length() - 4);
    }

    private static String truncate(String t) {
        return t.length() <= 80 ? t : t.substring(0, 80);
    }

    private static String encode(String at, UUID id) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString((at + "|" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String[] decode(String cursor) {
        try {
            String[] p = new String(java.util.Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8).split("\\|");
            if (p.length != 2) {
                throw new IllegalArgumentException();
            }
            UUID.fromString(p[1]);
            return p;
        } catch (RuntimeException e) {
            throw new InboxException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR");
        }
    }
}
