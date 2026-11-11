package com.nabd.hms.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** notification_requests (V1870). RLS-scoped: callers bind the tenant first and every statement names it. */
@Repository
class NotificationRequestRepository {

    record Row(UUID id, UUID tenantId, String purpose, UUID recipientPatientId, byte[] paramsEnc, String language,
               String channelPolicy, Instant notBefore, Instant notAfter, String status, String subjectType, UUID subjectId) {
    }

    private final JdbcTemplate jdbc;

    NotificationRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true when inserted, false when (tenant, dedupe key) already existed */
    boolean insert(UUID id, UUID tenantId, String purpose, String subjectType, UUID subjectId, UUID patientId, byte[] paramsEnc,
                   String language, String priority, String channelPolicy, Instant notBefore, Instant notAfter,
                   String dedupeKey, String status) {
        return jdbc.update("""
                INSERT INTO notification_requests (id, tenant_id, purpose, subject_type, subject_id, recipient_patient_id,
                    params_enc, language, priority, channel_policy, not_before, not_after, dedupe_key, status)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (tenant_id, dedupe_key) DO NOTHING
                """, id, tenantId, purpose, subjectType, subjectId, patientId, paramsEnc, language, priority, channelPolicy,
                ts(notBefore), ts(notAfter), dedupeKey, status) == 1;
    }

    Row byDedupeKey(UUID tenantId, String dedupeKey) {
        return jdbc.queryForObject(SELECT + "WHERE tenant_id = ? AND dedupe_key = ?", MAPPER, tenantId, dedupeKey);
    }

    Optional<Row> find(UUID tenantId, UUID id) {
        return jdbc.query(SELECT + "WHERE tenant_id = ? AND id = ?", MAPPER, tenantId, id).stream().findFirst();
    }

    /** Cross-tenant by design (the caller does not know the tenant yet): V1870 notification_request_tenant. */
    Optional<UUID> tenantOf(UUID id) {
        return Optional.ofNullable(jdbc.queryForObject("SELECT notification_request_tenant(?)", UUID.class, id));
    }

    /** Pending requests about the subject become 'cancelled'; returns their ids. */
    List<UUID> cancelBySubject(UUID tenantId, String subjectType, UUID subjectId) {
        return jdbc.queryForList("""
                UPDATE notification_requests SET status = 'cancelled'
                WHERE tenant_id = ? AND subject_type = ? AND subject_id = ? AND status = 'requested' RETURNING id
                """, UUID.class, tenantId, subjectType, subjectId);
    }

    /** Compare-and-set away from 'requested'. */
    boolean move(UUID tenantId, UUID id, String to) {
        return jdbc.update("UPDATE notification_requests SET status = ? WHERE tenant_id = ? AND id = ? AND status = 'requested'",
                to, tenantId, id) == 1;
    }

    /** Latest consent row of the type decides, like WhatsAppMessageRepository.hasMessagingConsent. */
    boolean hasConsent(UUID tenantId, UUID patientId, String consentType) {
        return jdbc.query("""
                SELECT withdrawn_at IS NULL FROM consents
                WHERE tenant_id = ? AND patient_id = ? AND consent_type = ? ORDER BY created_at DESC LIMIT 1
                """, (rs, i) -> rs.getBoolean(1), tenantId, patientId, consentType).stream().findFirst().orElse(false);
    }

    Optional<String> patientPhone(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT phone FROM patients WHERE tenant_id = ? AND id = ?", (rs, i) -> rs.getString(1), tenantId, patientId)
                .stream().findFirst();
    }

    private static java.sql.Timestamp ts(Instant i) {
        return i == null ? null : java.sql.Timestamp.from(i);
    }

    private static final String SELECT = """
            SELECT id, tenant_id, purpose, recipient_patient_id, params_enc, language, channel_policy, not_before, not_after, status, subject_type, subject_id
            FROM notification_requests\s""";

    private static final org.springframework.jdbc.core.RowMapper<Row> MAPPER = (rs, i) -> new Row(
            rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("purpose"),
            rs.getObject("recipient_patient_id", UUID.class), rs.getBytes("params_enc"), rs.getString("language"),
            rs.getString("channel_policy"),
            rs.getTimestamp("not_before") == null ? null : rs.getTimestamp("not_before").toInstant(),
            rs.getTimestamp("not_after") == null ? null : rs.getTimestamp("not_after").toInstant(), rs.getString("status"),
            rs.getString("subject_type"), rs.getObject("subject_id", UUID.class));
}
