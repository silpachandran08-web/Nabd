package com.nabd.hms.clinical.derm;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** CLN-047: photo_consents and clinical_photos (tenant RLS). Every query also names the tenant. */
@Repository
class DermPhotoRepository {

    record ConsentRow(UUID id, String scope, String status, String signerKind, Instant recordedAt) {
    }

    record PhotoRow(UUID id, UUID queueEntryId, LocalDate visitDate, UUID uploadId, String siteJson, String note, Instant takenAt,
                    String consentScope) {
    }

    private final JdbcTemplate jdbc;

    DermPhotoRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<ConsentRow> activeConsent(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT id, scope, status, signer_kind, recorded_at FROM photo_consents WHERE tenant_id = ? AND patient_id = ? AND status = 'active'",
                (rs, i) -> new ConsentRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant()), tenantId, patientId).stream().findFirst();
    }

    UUID insertConsent(UUID tenantId, UUID patientId, String scope, String signerKind, UUID signatureFileId, UUID by, Instant at) {
        return jdbc.queryForObject("INSERT INTO photo_consents (tenant_id, patient_id, scope, signer_kind, signature_file_id, recorded_by, recorded_at) "
                + "VALUES (?,?,?,?,?,?,?) RETURNING id", UUID.class, tenantId, patientId, scope, signerKind, signatureFileId, by, Timestamp.from(at));
    }

    /** Withdraws the active consent; the id of the withdrawn row, empty when there was none. */
    Optional<UUID> withdraw(UUID tenantId, UUID patientId, UUID by, Instant at) {
        return jdbc.query("UPDATE photo_consents SET status = 'withdrawn', withdrawn_at = ?, withdrawn_by = ? "
                        + "WHERE tenant_id = ? AND patient_id = ? AND status = 'active' RETURNING id",
                (rs, i) -> rs.getObject(1, UUID.class), Timestamp.from(at), by, tenantId, patientId).stream().findFirst();
    }

    /** Hides (never deletes) every visible photo of the patient; the number hidden. */
    int hideAll(UUID tenantId, UUID patientId, Instant at) {
        return jdbc.update("UPDATE clinical_photos SET hidden_at = ? WHERE tenant_id = ? AND patient_id = ? AND hidden_at IS NULL",
                Timestamp.from(at), tenantId, patientId);
    }

    Optional<UUID> visitPatient(UUID tenantId, UUID queueEntryId) {
        return jdbc.query("SELECT patient_id FROM queue_entries WHERE tenant_id = ? AND id = ?",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId, queueEntryId).stream().findFirst();
    }

    /** The photo already registered for this upload: (photo id, patient id). */
    Optional<UUID[]> byUpload(UUID tenantId, UUID uploadId) {
        return jdbc.query("SELECT id, patient_id FROM clinical_photos WHERE tenant_id = ? AND upload_id = ?",
                (rs, i) -> new UUID[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}, tenantId, uploadId).stream().findFirst();
    }

    UUID insertPhoto(UUID tenantId, UUID patientId, UUID queueEntryId, UUID uploadId, String siteJson, String note, UUID consentId, UUID by, Instant at) {
        return jdbc.queryForObject("INSERT INTO clinical_photos (tenant_id, patient_id, queue_entry_id, upload_id, site, note, taken_at, consent_id, created_by) "
                        + "VALUES (?,?,?,?,?::jsonb,?,?,?,?) RETURNING id", UUID.class,
                tenantId, patientId, queueEntryId, uploadId, siteJson, note, Timestamp.from(at), consentId, by);
    }

    /** Visible photos, oldest first; region filters on site->>'region' when given. */
    List<PhotoRow> visible(UUID tenantId, UUID patientId, String region) {
        return jdbc.query("SELECT p.id, p.queue_entry_id, q.queue_date, p.upload_id, p.site::text, p.note, p.taken_at, c.scope "
                        + "FROM clinical_photos p JOIN photo_consents c ON c.tenant_id = p.tenant_id AND c.id = p.consent_id "
                        + "LEFT JOIN queue_entries q ON q.tenant_id = p.tenant_id AND q.id = p.queue_entry_id "
                        + "WHERE p.tenant_id = ? AND p.patient_id = ? AND p.hidden_at IS NULL AND (?::text IS NULL OR p.site->>'region' = ?) "
                        + "ORDER BY p.taken_at, p.id",
                (rs, i) -> new PhotoRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, LocalDate.class), rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6),
                        rs.getTimestamp(7).toInstant(), rs.getString(8)), tenantId, patientId, region, region);
    }

    int hiddenCount(UUID tenantId, UUID patientId) {
        return jdbc.queryForObject("SELECT count(*) FROM clinical_photos WHERE tenant_id = ? AND patient_id = ? AND hidden_at IS NOT NULL",
                Integer.class, tenantId, patientId);
    }

    boolean hiddenUpload(UUID tenantId, UUID uploadId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM clinical_photos WHERE tenant_id = ? AND upload_id = ? AND hidden_at IS NOT NULL)",
                Boolean.class, tenantId, uploadId));
    }
}
