package com.nabd.hms.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** whatsapp_messages (V46). Everything but applyStatus is RLS-scoped — callers set TenantContext first. */
@Repository
class WhatsAppMessageRepository {

    /** templateStatus is the status of the template's ACTIVE version right now, not when queued — null for OTP-style sends.
     * COM-013: sendName is the active version's Meta name (a newer approved version takes over queued sends too),
     * templateCategory the effective one (Meta's after a re-categorisation), notAfter the send window's end. */
    record Message(UUID id, UUID patientId, String toPhone, String templateName, String language,
                   String paramsJson, String status, String templateStatus, String templateKey, String templateCategory,
                   String sendName, java.time.Instant pausedUntil, java.time.Instant notAfter) {
    }

    private final JdbcTemplate jdbc;

    WhatsAppMessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    UUID insert(UUID tenantId, UUID patientId, String toPhone, UUID templateId, String templateName, String language,
                String paramsJson, java.time.Instant notAfter) {
        return jdbc.queryForObject("""
                INSERT INTO whatsapp_messages (tenant_id, patient_id, to_phone, template_id, template_name, language, params, not_after)
                VALUES (?,?,?,?,?,?,?::jsonb,?) RETURNING id
                """, UUID.class, tenantId, patientId, toPhone, templateId, templateName, language, paramsJson,
                notAfter == null ? null : java.sql.Timestamp.from(notAfter));
    }

    /** OPS-014: locks the message row until the caller's transaction ends, so two workers holding the same
     * outbox event (one with an expired lease) serialize here and the second sees the first one's result. */
    Optional<Message> findForUpdate(UUID id) {
        return jdbc.query("""
                SELECT m.*, COALESCE(a.status, t.status) AS template_status, t.name AS template_key,
                       COALESCE(a.meta_category, a.category, t.meta_category, t.category) AS template_category,
                       COALESCE(a.meta_name, m.template_name) AS send_name, COALESCE(a.paused_until, t.paused_until) AS paused_until
                FROM whatsapp_messages m
                LEFT JOIN whatsapp_templates t ON t.id = m.template_id
                LEFT JOIN whatsapp_templates a ON a.tenant_id = t.tenant_id AND a.name = t.name AND a.language = t.language AND a.active
                WHERE m.id = ?
                FOR UPDATE OF m
                """, (rs, i) -> new Message(
                rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class), rs.getString("to_phone"),
                rs.getString("template_name"), rs.getString("language"), rs.getString("params"),
                rs.getString("status"), rs.getString("template_status"), rs.getString("template_key"),
                rs.getString("template_category"), rs.getString("send_name"), instant(rs.getTimestamp("paused_until")),
                instant(rs.getTimestamp("not_after"))), id).stream().findFirst();
    }

    private static java.time.Instant instant(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    /** Consents are append-only (a withdrawal is its own newer row — see PatientRepository), so the
     * latest 'messaging' row decides. No row at all = never consented = blocked. */
    boolean hasMessagingConsent(UUID patientId) {
        return hasConsent(patientId, "messaging");
    }

    /** COM-013: a template Meta re-categorised as MARKETING also needs the patient's 'marketing' consent. */
    boolean hasConsent(UUID patientId, String consentType) {
        Boolean granted = jdbc.query("""
                SELECT withdrawn_at IS NULL FROM consents
                WHERE patient_id = ? AND consent_type = ?
                ORDER BY created_at DESC LIMIT 1
                """, (rs, i) -> rs.getBoolean(1), patientId, consentType).stream().findFirst().orElse(false);
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

    /** COM-013: the send window closed before the send could go (also for a stale 'sending' claim whose call never started). */
    boolean markExpired(UUID id) {
        return jdbc.update("UPDATE whatsapp_messages SET status = 'expired' WHERE id = ? AND status IN ('scheduled', 'queued', 'sending')", id) == 1;
    }

    void markBlocked(UUID id, String reason) {
        jdbc.update("UPDATE whatsapp_messages SET status = 'blocked', error = ? WHERE id = ?", reason, id);
    }

    /** Cross-tenant by design — see apply_whatsapp_status in V46. */
    void applyStatus(String wamid, String status, String error) {
        jdbc.queryForList("SELECT apply_whatsapp_status(?, ?, ?)", wamid, status, error);
    }
}
