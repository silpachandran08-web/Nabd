package com.nabd.hms.shared.files;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
class FileRepository {

    record Row(UUID id, UUID tenantId, String purpose, String storageKey, String quarantineKey, String declaredType,
               String detectedType, long sizeBytes, String status, UUID patientId, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;

    FileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID id, UUID tenantId, String purpose, String quarantineKey, String storageKey, String declaredType,
                long size, UUID uploadedBy) {
        jdbc.update("INSERT INTO file_objects (id, tenant_id, purpose, storage_key, quarantine_key, declared_type, size_bytes, uploaded_by) "
                + "VALUES (?,?,?,?,?,?,?,?)", id, tenantId, purpose, storageKey, quarantineKey, declaredType, size, uploadedBy);
    }

    void insertGenerated(UUID id, UUID tenantId, String purpose, String storageKey, String type, long size, byte[] sha256, UUID by,
                         UUID patientId, UUID visitId, Instant at) {
        jdbc.update("INSERT INTO file_objects (id, tenant_id, purpose, storage_key, declared_type, detected_type, size_bytes, sha256, status, "
                + "scan_engine, scanned_at, uploaded_by, patient_id, visit_id) VALUES (?,?,?,?,?,?,?,?,'available','generated',?,?,?,?)",
                id, tenantId, purpose, storageKey, type, type, size, sha256, Timestamp.from(at), by, patientId, visitId);
    }

    Optional<Row> find(UUID tenantId, UUID id) {
        return jdbc.query("SELECT * FROM file_objects WHERE tenant_id = ? AND id = ?", (rs, i) -> new Row(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("purpose"),
                rs.getString("storage_key"), rs.getString("quarantine_key"), rs.getString("declared_type"),
                rs.getString("detected_type"), rs.getLong("size_bytes"), rs.getString("status"),
                rs.getObject("patient_id", UUID.class), rs.getTimestamp("created_at").toInstant()), tenantId, id).stream().findFirst();
    }

    /** Compare-and-set so that two completes cannot both start a scan. */
    boolean markPendingScan(UUID tenantId, UUID id, byte[] sha256) {
        return jdbc.update("UPDATE file_objects SET status = 'pending_scan', sha256 = ? WHERE tenant_id = ? AND id = ? AND status = 'awaiting_upload'",
                sha256, tenantId, id) == 1;
    }

    void finish(UUID tenantId, UUID id, String status, String detectedType, long size, byte[] sha256, String engine, Instant at) {
        jdbc.update("UPDATE file_objects SET status = ?, detected_type = ?, size_bytes = ?, sha256 = ?, scan_engine = ?, scanned_at = ?, "
                + "quarantine_key = CASE WHEN ? = 'available' THEN NULL ELSE quarantine_key END WHERE tenant_id = ? AND id = ? AND status = 'pending_scan'",
                status, detectedType, size, sha256, engine, Timestamp.from(at), status, tenantId, id);
    }

    /** Only a file that is available and not yet bound; a second bind to another patient is refused. */
    boolean bindPatient(UUID tenantId, UUID id, UUID patientId, UUID visitId) {
        return jdbc.update("UPDATE file_objects SET patient_id = ?, visit_id = ? WHERE tenant_id = ? AND id = ? AND status = 'available' "
                + "AND (patient_id IS NULL OR patient_id = ?)", patientId, visitId, tenantId, id, patientId) == 1;
    }

    /** The clinic owners (built-in role) get one in-app alert per blocked file. */
    void alertOwners(UUID tenantId, UUID fileId, String reason) {
        jdbc.update("INSERT INTO file_blocked_alerts (tenant_id, file_id, owner_staff_id, reason) "
                + "SELECT ?, ?, s.id, ? FROM staff s JOIN roles r ON r.id = s.role_id "
                + "WHERE s.tenant_id = ? AND r.built_in = true AND s.status = 'active' ON CONFLICT (file_id, owner_staff_id) DO NOTHING",
                tenantId, fileId, reason, tenantId);
    }
}
