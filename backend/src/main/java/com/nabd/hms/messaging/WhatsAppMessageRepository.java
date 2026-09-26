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

    Optional<Message> find(UUID id) {
        return jdbc.query("""
                SELECT m.*, t.status AS template_status FROM whatsapp_messages m
                LEFT JOIN whatsapp_templates t ON t.id = m.template_id
                WHERE m.id = ?
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

    void markSent(UUID id, String wamid) {
        jdbc.update("UPDATE whatsapp_messages SET status = 'sent', wamid = ? WHERE id = ?", wamid, id);
    }

    void markFailed(UUID id, String error) {
        jdbc.update("UPDATE whatsapp_messages SET status = 'failed', error = ? WHERE id = ?", error, id);
    }

    void markBlocked(UUID id, String reason) {
        jdbc.update("UPDATE whatsapp_messages SET status = 'blocked', error = ? WHERE id = ?", reason, id);
    }

    /** Cross-tenant by design — see apply_whatsapp_status in V46. */
    void applyStatus(String wamid, String status, String error) {
        jdbc.queryForList("SELECT apply_whatsapp_status(?, ?, ?)", wamid, status, error);
    }
}
