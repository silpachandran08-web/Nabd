package com.nabd.comms.inbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * COM-032: what reception does in a thread. Free text only while the 24 h window is open (409 SESSION_WINDOW_CLOSED), approved templates
 * otherwise; assignment, bot/human mode, call-outcome notes, canned replies and the clinical tag. Every action writes one inbox_audit row:
 * staff id, thread id and, for text, its SHA-256 (never the text). Text at rest is encrypted. The message is queued here; the sender
 * (COM-020) puts it on the wire, so "delivered" is the provider's status callback, not this call.
 */
@Service
@ConditionalOnProperty("comms.field-key")
public class ReplyService {

    public static final int MAX_TEXT = 1024;
    static final Set<String> OUTCOMES = Set.of("reached", "no_answer", "call_back", "booked");

    public record Sent(UUID messageId, String status) {}

    public record ThreadState(UUID id, String mode, UUID assignedTo, boolean clinical, boolean clinicalSuggested) {}

    public record Note(UUID id, String outcome, String note, UUID staffRef, Instant at) {}

    public record Canned(UUID id, String title, String body, String language) {}

    public record SendableTemplate(UUID id, String name, String language, String category, String body, int paramCount) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final FieldCrypto crypto;
    private final Clock clock;

    public ReplyService(JdbcTemplate jdbc, TransactionTemplate tx, FieldCrypto crypto, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.crypto = crypto;
        this.clock = clock;
    }

    /** Free text inside the window. {@code overrideClinical} = staff saw the secure-link warning and still send. */
    public Sent reply(UUID tenant, UUID threadId, UUID staff, String text, boolean overrideClinical, String idemKey) {
        if (text == null || text.isBlank() || text.length() > MAX_TEXT) {
            throw new InboxException(HttpStatus.BAD_REQUEST, "TEXT_INVALID");
        }
        return tx.execute(s -> {
            bind(tenant);
            ThreadRow t = lock(threadId);
            Sent again = replay(idemKey);
            if (again != null) {
                return again;
            }
            if (t.optedOut()) {
                throw new InboxException(HttpStatus.CONFLICT, "OPTED_OUT");
            }
            if (t.windowExpires() == null || !t.windowExpires().isAfter(clock.instant())) {
                throw new InboxException(HttpStatus.CONFLICT, "SESSION_WINDOW_CLOSED");
            }
            if (!overrideClinical && ClinicalText.looksLikeResultOrDiagnosis(text)) {
                throw new InboxException(HttpStatus.UNPROCESSABLE_ENTITY, "TEXT_LOOKS_CLINICAL"); // send a secure link instead, or override
            }
            UUID id = enqueue(tenant, t, staff, "session_text", "und", null, crypto.encrypt(text), null, idemKey);
            audit(tenant, threadId, staff, "reply", sha256(text), null);
            return new Sent(id, "queued");
        });
    }

    /** An approved template; the only way to write first after the window closed. */
    public Sent sendTemplate(UUID tenant, UUID threadId, UUID staff, UUID templateId, List<String> params, String idemKey) {
        List<String> p = params == null ? List.of() : params;
        return tx.execute(s -> {
            bind(tenant);
            ThreadRow t = lock(threadId);
            Sent again = replay(idemKey);
            if (again != null) {
                return again;
            }
            if (t.optedOut()) {
                throw new InboxException(HttpStatus.CONFLICT, "OPTED_OUT");
            }
            var tpl = jdbc.query("SELECT name, language, param_count FROM whatsapp_templates WHERE id = ? AND status = 'approved'",
                    (rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getInt(3)}, templateId);
            if (tpl.isEmpty() || (int) tpl.get(0)[2] != p.size() || p.stream().anyMatch(x -> x == null || x.isBlank() || x.length() > 200)) {
                throw new InboxException(HttpStatus.UNPROCESSABLE_ENTITY, "TEMPLATE_NOT_SENDABLE");
            }
            UUID id = enqueue(tenant, t, staff, (String) tpl.get(0)[0], (String) tpl.get(0)[1], templateId, null,
                    crypto.encrypt(String.join("\u001f", p)), idemKey); // params_enc: unit-separator joined, like the other template sends
            audit(tenant, threadId, staff, "template_send", null, "template=" + templateId);
            return new Sent(id, "queued");
        });
    }

    /** Approved templates a staff member may pick (Comms owns the template table); language filter optional. */
    public List<SendableTemplate> sendableTemplates(UUID tenant, String language) {
        return tx.execute(s -> {
            bind(tenant);
            return jdbc.query("SELECT id, name, language, category, body, param_count FROM whatsapp_templates WHERE status = 'approved' "
                            + "AND (?::text IS NULL OR language = ?) ORDER BY name, language",
                    (rs, i) -> new SendableTemplate(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6)),
                    language, language);
        });
    }

    public ThreadState assign(UUID tenant, UUID threadId, UUID staff, UUID assignee) {
        return tx.execute(s -> {
            bind(tenant);
            lock(threadId);
            jdbc.update("UPDATE inbox_threads SET assigned_staff_ref = ? WHERE id = ?", assignee, threadId);
            audit(tenant, threadId, staff, "assign", null, assignee == null ? "unassigned" : "to=" + assignee);
            return state(threadId);
        });
    }

    /** Bot to human the moment staff types, human to bot when they hand back. The Edge reads the mode before it answers (see the report). */
    public ThreadState setMode(UUID tenant, UUID threadId, UUID staff, String mode) {
        if (!"bot".equals(mode) && !"human".equals(mode)) {
            throw new InboxException(HttpStatus.BAD_REQUEST, "INVALID_MODE");
        }
        return tx.execute(s -> {
            bind(tenant);
            ThreadRow t = lock(threadId);
            if (!mode.equals(t.mode())) {
                jdbc.update("UPDATE inbox_threads SET mode = ? WHERE id = ?", mode, threadId);
                audit(tenant, threadId, staff, "mode", null, "mode=" + mode);
            }
            return state(threadId);
        });
    }

    public Note addNote(UUID tenant, UUID threadId, UUID staff, String outcome, String note) {
        if (!OUTCOMES.contains(outcome) || (note != null && note.length() > 1024)) {
            throw new InboxException(HttpStatus.BAD_REQUEST, "NOTE_INVALID");
        }
        return tx.execute(s -> {
            bind(tenant);
            lock(threadId);
            UUID id = UUID.randomUUID();
            Instant at = clock.instant();
            jdbc.update("INSERT INTO thread_notes (id, tenant_id, thread_id, staff_ref, outcome, note_enc, at) VALUES (?,?,?,?,?,?,?)",
                    id, tenant, threadId, staff, outcome, note == null || note.isBlank() ? null : crypto.encrypt(note), Timestamp.from(at));
            audit(tenant, threadId, staff, "note", note == null ? null : sha256(note), "outcome=" + outcome);
            return new Note(id, outcome, note, staff, at);
        });
    }

    public List<Note> notes(UUID tenant, UUID threadId) {
        return tx.execute(s -> {
            bind(tenant);
            lock(threadId);
            return jdbc.query("SELECT id, outcome, note_enc, staff_ref, at FROM thread_notes WHERE thread_id = ? ORDER BY at DESC, id",
                    (rs, i) -> new Note(rs.getObject(1, UUID.class), rs.getString(2), rs.getBytes(3) == null ? null : crypto.decrypt(rs.getBytes(3)),
                            rs.getObject(4, UUID.class), rs.getTimestamp(5).toInstant()), threadId);
        });
    }

    /** Staff accept or clear the clinical tag the keyword rules suggested; tagged threads are what the nurse inbox lists (filter=clinical). */
    public ThreadState tagClinical(UUID tenant, UUID threadId, UUID staff, boolean clinical) {
        return tx.execute(s -> {
            bind(tenant);
            lock(threadId);
            jdbc.update("UPDATE inbox_threads SET tags = CASE WHEN ? THEN (SELECT array_agg(DISTINCT x) FROM unnest(tags || ARRAY['clinical']) x) "
                    + "ELSE array_remove(tags, 'clinical') END, clinical_suggested = false WHERE id = ?", clinical, threadId);
            audit(tenant, threadId, staff, "tag_clinical", null, "clinical=" + clinical);
            return state(threadId);
        });
    }

    public List<Canned> canned(UUID tenant, String language) {
        return tx.execute(s -> {
            bind(tenant);
            return jdbc.query("SELECT id, title, body, language FROM canned_replies WHERE (?::text IS NULL OR language = ?) ORDER BY title",
                    (rs, i) -> new Canned(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)), language, language);
        });
    }

    public Canned createCanned(UUID tenant, UUID staff, String title, String body, String language) {
        if (title == null || title.isBlank() || title.length() > 80 || body == null || body.isBlank() || body.length() > MAX_TEXT
                || language == null || !language.matches("[a-z]{2,3}(-[A-Za-z0-9]{2,8})?")) {
            throw new InboxException(HttpStatus.BAD_REQUEST, "CANNED_INVALID");
        }
        return tx.execute(s -> {
            bind(tenant);
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO canned_replies (id, tenant_id, title, body, language, created_by) VALUES (?,?,?,?,?,?)", id, tenant, title.trim(), body, language, staff);
            return new Canned(id, title.trim(), body, language);
        });
    }

    // ---- helpers ----

    private record ThreadRow(UUID id, byte[] phoneEnc, byte[] phoneHash, UUID patientRef, UUID senderAccount, Instant windowExpires, boolean optedOut, String mode) {}

    private ThreadRow lock(UUID threadId) {
        return jdbc.query("SELECT id, phone_enc, phone_hash, patient_ref, sender_account_id, window_expires_at, opted_out, mode FROM inbox_threads WHERE id = ? FOR UPDATE",
                (rs, i) -> new ThreadRow(rs.getObject(1, UUID.class), rs.getBytes(2), rs.getBytes(3), rs.getObject(4, UUID.class), rs.getObject(5, UUID.class),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), rs.getBoolean(7), rs.getString(8)), threadId)
                .stream().findFirst().orElseThrow(() -> new InboxException(HttpStatus.NOT_FOUND, "THREAD_NOT_FOUND"));
    }

    private Sent replay(String idemKey) {
        if (idemKey == null) {
            return null;
        }
        return jdbc.query("SELECT id FROM outbound_messages WHERE idempotency_key = ?", (rs, i) -> new Sent(rs.getObject(1, UUID.class), "queued"), idemKey)
                .stream().findFirst().orElse(null);
    }

    private UUID enqueue(UUID tenant, ThreadRow t, UUID staff, String templateName, String language, UUID templateId, byte[] freeText, byte[] params, String idemKey) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                INSERT INTO outbound_messages (id, tenant_id, channel, sender_account_id, patient_ref, to_address_enc, to_hash, template_id, template_name, language,
                  params_enc, priority, status, thread_id, free_text_enc, author_staff_ref, idempotency_key, created_at, updated_at)
                VALUES (?,?, 'whatsapp', ?,?,?,?,?,?,?, ?, 'transactional', 'queued', ?,?,?,?, ?,?)""",
                id, tenant, t.senderAccount(), t.patientRef(), t.phoneEnc(), t.phoneHash(), templateId, templateName, language, params, t.id(), freeText, staff, idemKey, now, now);
        // staff answered: the patient is no longer waiting, and typing takes the thread from the bot
        jdbc.update("UPDATE inbox_threads SET last_outbound_at = GREATEST(coalesce(last_outbound_at, '-infinity'), ?), waiting_since = NULL, mode = 'human' WHERE id = ?", now, t.id());
        return id;
    }

    private ThreadState state(UUID threadId) {
        return jdbc.queryForObject("SELECT id, mode, assigned_staff_ref, 'clinical' = ANY(tags), clinical_suggested FROM inbox_threads WHERE id = ?",
                (rs, i) -> new ThreadState(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getBoolean(4), rs.getBoolean(5)), threadId);
    }

    private void audit(UUID tenant, UUID threadId, UUID staff, String action, byte[] textHash, String detail) {
        jdbc.update("INSERT INTO inbox_audit (id, tenant_id, thread_id, staff_ref, action, text_hash, detail, at) VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), tenant, threadId, staff, action, textHash, detail, Timestamp.from(clock.instant()));
    }

    private void bind(UUID tenant) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
    }

    static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
