package com.nabd.hms.clinical;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
class AttachmentRepository {

    record Row(UUID id, UUID visitId, UUID patientId, UUID fileId, String kind, String note, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;

    AttachmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<UUID> visitPatient(UUID tenantId, UUID visitId) {
        return jdbc.query("SELECT patient_id FROM queue_entries WHERE tenant_id = ? AND id = ?",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId, visitId).stream().findFirst();
    }

    UUID insert(UUID tenantId, UUID visitId, UUID patientId, UUID fileId, String kind, String note, UUID by) {
        return jdbc.queryForObject("INSERT INTO visit_attachments (tenant_id, visit_id, patient_id, file_id, kind, note, attached_by) "
                + "VALUES (?,?,?,?,?,?,?) RETURNING id", UUID.class, tenantId, visitId, patientId, fileId, kind, note, by);
    }

    List<Row> list(UUID tenantId, UUID visitId) {
        return jdbc.query("SELECT * FROM visit_attachments WHERE tenant_id = ? AND visit_id = ? ORDER BY created_at DESC, id",
                (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getObject("visit_id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getObject("file_id", UUID.class), rs.getString("kind"), rs.getString("note"), rs.getTimestamp("created_at").toInstant()),
                tenantId, visitId);
    }
}
