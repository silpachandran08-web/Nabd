package com.nabd.hms.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** whatsapp_messages (V46). Everything but applyStatus is RLS-scoped — callers set TenantContext first. */
@Repository
class WhatsAppMessageRepository {

    /** templateStatus is the template's status right now, not when queued — null for OTP-style sends. */
    record Message(UUID id, UUID patientId, String toPhone, String templateName, String language,
                   String paramsJson, String status, String templateStatus) {
    }

    private final JdbcTemplate jdbc;

    WhatsAppMessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    UUID insert(UUID tenantId, UUID patientId, String toPhone, UUID templateId, String templateName, String language,
                String paramsJson) {
        return jdbc.queryForObject("""
                INSERT INTO whatsapp_messages (tenant_id, patient_id, to_phone, template_id, template_name, language, params)
                VALUES (?,?,?,?,?,?,?::jsonb) RETURNING id
                """, UUID.class, tenantId, patientId, toPhone, templateId, templateName, language, paramsJson);
    }

    /** OPS-014: locks the message row until the caller's transaction ends, so two workers holding the same
     * outbox event (one with an expired lease) serialize here and the second sees the first one's result. */
    Optional<Message> findForUpdate(UUID id) {
        return jdbc.query("""
                SELECT m.*, t.status AS template_status FROM whatsapp_messages m
                LEFT JOIN whatsapp_templates t ON t.id = m.template_id
                WHERE m.id = ?
                FOR UPDATE OF m
                """, (rs, i) -> new Message(
                rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class), rs.getString("to_phone"),
                rs.getString("template_name"), rs.getString("language"), rs.getString("params"),
                rs.getString("status"), rs.getString("template_status")), id).stream().findFirst();
    }

    /** Consents are append-only (a withdrawal is its own newer row — see PatientRepository), so the
     * latest 'messaging' row decides. No row at all = never consented = blocked. */
    boolean hasMessagingConsent(UUID patientId) {
        Boolean granted = jdbc.query("""
                SELECT withdrawn_at IS NULL FROM consents
                WHERE patient_id = ? AND consent_type = 'messaging'
                ORDER BY created_at DESC LIMIT 1
                """, (rs, i) -> rs.getBoolean(1), patientId).stream().findFirst().orElse(false);
        return granted;
    }

    /** OPS-012: committed before the provider call, so a second worker can see a send is (or was) in flight.
     * COM-003: bumps attempts and returns it: the fencing token this claim must present to finish the send. */
    int markSending(UUID id) {
        return jdbc.queryForObject("""
                UPDATE whatsapp_messages SET status = 'sending', attempts = attempts + 1, last_attempt_at = now()
                WHERE id = ? AND status IN ('queued', 'sending') RETURNING attempts
                """, Integer.class, id);
    }

    /** True while a 'sending' row is younger than {@code seconds}: its sender may still be mid-call. */
    boolean sendInFlight(UUID id, int seconds) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT status = 'sending' AND updated_at > now() - make_interval(secs => ?) FROM whatsapp_messages WHERE id = ?",
                Boolean.class, seconds, id));
    }

    /** The provider call failed in a way that is safe to retry: back to queued for the next attempt. Fenced: a stale
     * worker (older attempt) changes nothing. */
    void requeue(UUID id, int attempt) {
        jdbc.update("UPDATE whatsapp_messages SET status = 'queued' WHERE id = ? AND status = 'sending' AND attempts = ?", id, attempt);
    }

    int markSent(UUID id, String wamid, int attempt) {
        return jdbc.update("UPDATE whatsapp_messages SET status = 'sent', wamid = ? WHERE id = ? AND status IN ('queued', 'sending') AND attempts = ?", wamid, id, attempt);
    }

    void markFailed(UUID id, String error) {
        jdbc.update("UPDATE whatsapp_messages SET status = 'failed', error = ? WHERE id = ? AND status IN ('queued', 'sending')", error, id);
    }

    void markFailed(UUID id, String error, int attempt) {
        jdbc.update("UPDATE whatsapp_messages SET status = 'failed', error = ? WHERE id = ? AND status IN ('queued', 'sending') AND attempts = ?", error, id, attempt);
    }

    /** COM-003: only a message that has not started sending can be cancelled/expired; never touches 'sending'/'sent'. */
    boolean markTerminal(UUID id, String status) {
        return jdbc.update("UPDATE whatsapp_messages SET status = ? WHERE id = ? AND status IN ('scheduled', 'queued')", status, id) == 1;
    }

    void markBlocked(UUID id, String reason) {
        jdbc.update("UPDATE whatsapp_messages SET status = 'blocked', error = ? WHERE id = ?", reason, id);
    }

    /** Cross-tenant by design — see apply_whatsapp_status in V46. */
    void applyStatus(String wamid, String status, String error) {
        jdbc.queryForList("SELECT apply_whatsapp_status(?, ?, ?)", wamid, status, error);
    }
}
