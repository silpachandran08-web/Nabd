package com.nabd.hms.clinical;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

import static com.nabd.hms.clinical.AllergyModels.AllergyRow;

@Repository
class AllergyRepository {

    private final JdbcTemplate jdbc;

    AllergyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<AllergyRow> findActiveByPatient(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT * FROM patient_allergies WHERE tenant_id = ? AND patient_id = ? AND active " +
                        "ORDER BY recorded_at DESC",
                mapper(), tenantId, patientId);
    }

    UUID insert(UUID tenantId, UUID patientId, String substance, String severity, String reaction, UUID recordedBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO patient_allergies (id, tenant_id, patient_id, substance, severity, reaction, recorded_by) " +
                        "VALUES (?,?,?,?,?,?,?)",
                id, tenantId, patientId, substance, severity, reaction, recordedBy);
        return id;
    }

    /** Returns affected rows so the service can 404 on an id that doesn't belong to this tenant. */
    int deactivate(UUID tenantId, UUID id) {
        return jdbc.update("UPDATE patient_allergies SET active = false WHERE tenant_id = ? AND id = ?", tenantId, id);
    }

    private RowMapper<AllergyRow> mapper() {
        return (rs, i) -> new AllergyRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("patient_id")),
                rs.getString("substance"),
                rs.getString("severity"),
                rs.getString("reaction"),
                rs.getBoolean("active"),
                UUID.fromString(rs.getString("recorded_by")),
                rs.getTimestamp("recorded_at").toInstant());
    }
}
