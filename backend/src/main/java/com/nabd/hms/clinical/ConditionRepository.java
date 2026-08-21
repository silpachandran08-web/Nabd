package com.nabd.hms.clinical;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.nabd.hms.clinical.ConditionModels.ConditionRow;
import static com.nabd.hms.clinical.ConditionModels.DueConditionRow;

@Repository
class ConditionRepository {

    private final JdbcTemplate jdbc;

    ConditionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<ConditionRow> findActiveByPatient(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT * FROM chronic_conditions WHERE tenant_id = ? AND patient_id = ? AND status = 'active' " +
                        "ORDER BY recorded_at DESC",
                mapper(), tenantId, patientId);
    }

    UUID insert(UUID tenantId, UUID patientId, String condition, LocalDate reviewDueDate, UUID recordedBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO chronic_conditions (id, tenant_id, patient_id, condition, review_due_date, recorded_by) " +
                        "VALUES (?,?,?,?,?,?)",
                id, tenantId, patientId, condition, reviewDueDate == null ? null : Date.valueOf(reviewDueDate), recordedBy);
        return id;
    }

    int resolve(UUID tenantId, UUID id) {
        return jdbc.update("UPDATE chronic_conditions SET status = 'resolved' WHERE tenant_id = ? AND id = ?", tenantId, id);
    }

    /** NB-077: derived, not manually maintained — anything active whose review_due_date has arrived. */
    List<DueConditionRow> findDue(UUID tenantId, LocalDate asOf) {
        return jdbc.query("SELECT c.id, c.patient_id, p.name AS patient_name, c.condition, c.review_due_date " +
                        "FROM chronic_conditions c JOIN patients p ON p.id = c.patient_id " +
                        "WHERE c.tenant_id = ? AND c.status = 'active' AND c.review_due_date IS NOT NULL AND c.review_due_date <= ? " +
                        "ORDER BY c.review_due_date",
                (rs, i) -> new DueConditionRow(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("patient_id")),
                        rs.getString("patient_name"), rs.getString("condition"), rs.getDate("review_due_date").toLocalDate()),
                tenantId, Date.valueOf(asOf));
    }

    private RowMapper<ConditionRow> mapper() {
        return (rs, i) -> new ConditionRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("patient_id")),
                rs.getString("condition"),
                rs.getString("status"),
                rs.getDate("review_due_date") == null ? null : rs.getDate("review_due_date").toLocalDate(),
                UUID.fromString(rs.getString("recorded_by")),
                rs.getTimestamp("recorded_at").toInstant());
    }
}
