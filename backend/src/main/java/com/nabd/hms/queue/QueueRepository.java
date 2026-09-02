package com.nabd.hms.queue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.queue.QueueModels.QueueEntryRow;

@Repository
class QueueRepository {

    private static final String COLUMNS =
            "id, appointment_id, patient_id, doctor_id, department_id, parent_queue_entry_id, queue_date, token_number, " +
                    "status, priority, priority_reason, priority_flagged_by, priority_flagged_at, priority_acknowledged_by, " +
                    "priority_acknowledged_at, source, created_at ";

    private final JdbcTemplate jdbc;

    QueueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Serializes token assignment for one doctor+day so two concurrent check-ins can't compute the
     * same "next token number" — held for the rest of the transaction, released at commit/rollback.
     * The UNIQUE(tenant_id, doctor_id, queue_date, token_number) constraint is the backstop if this
     * were ever skipped, but the lock means it should never actually fire.
     */
    void lockDoctorDay(UUID doctorId, LocalDate date) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, doctorId + ":" + date);
    }

    int nextTokenNumber(UUID doctorId, LocalDate date) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(token_number), 0) FROM queue_entries WHERE doctor_id = ? AND queue_date = ?",
                Integer.class, doctorId, Date.valueOf(date));
        return (max == null ? 0 : max) + 1;
    }

    UUID insert(UUID tenantId, UUID appointmentId, UUID patientId, UUID doctorId, UUID departmentId,
                UUID parentQueueEntryId, LocalDate queueDate, int tokenNumber, String source, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO queue_entries (id, tenant_id, appointment_id, patient_id, doctor_id, department_id, " +
                        "parent_queue_entry_id, queue_date, token_number, source, status) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id, tenantId, appointmentId, patientId, doctorId, departmentId, parentQueueEntryId,
                Date.valueOf(queueDate), tokenNumber, source, status);
        return id;
    }

    /** Server-derived, never client-supplied — a doctor's own assigned department, falling back to
     * the tenant's single default department if nobody has assigned one yet (so check-in never
     * breaks for a doctor no one has gotten around to configuring). */
    UUID findCheckInDepartment(UUID tenantId, UUID doctorId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(s.department_id, (SELECT id FROM departments WHERE tenant_id = ? AND is_default)) " +
                        "FROM staff s WHERE s.tenant_id = ? AND s.id = ?",
                UUID.class, tenantId, tenantId, doctorId);
    }

    /** The owner-designed transfer graph, read narrowly here rather than injecting DepartmentRepository. */
    boolean transferAllowed(UUID tenantId, UUID fromDepartmentId, UUID toDepartmentId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM department_transfers WHERE tenant_id = ? AND from_department_id = ? AND to_department_id = ?)",
                Boolean.class, tenantId, fromDepartmentId, toDepartmentId);
        return Boolean.TRUE.equals(exists);
    }

    Optional<QueueEntryRow> findById(UUID tenantId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + "FROM queue_entries WHERE tenant_id = ? AND id = ?",
                mapper(), tenantId, id).stream().findFirst();
    }

    List<QueueEntryRow> listForDay(UUID tenantId, UUID doctorId, UUID departmentId, LocalDate date, boolean priorityOnly) {
        String priorityGate = priorityOnly ? "AND priority = true " : "";
        if (doctorId != null) {
            return jdbc.query("SELECT " + COLUMNS + "FROM queue_entries " +
                            "WHERE tenant_id = ? AND doctor_id = ? AND queue_date = ? " + priorityGate +
                            "ORDER BY priority DESC, token_number",
                    mapper(), tenantId, doctorId, Date.valueOf(date));
        }
        if (departmentId != null) {
            return jdbc.query("SELECT " + COLUMNS + "FROM queue_entries " +
                            "WHERE tenant_id = ? AND department_id = ? AND queue_date = ? " + priorityGate +
                            "ORDER BY doctor_id, priority DESC, token_number",
                    mapper(), tenantId, departmentId, Date.valueOf(date));
        }
        return jdbc.query("SELECT " + COLUMNS + "FROM queue_entries " +
                        "WHERE tenant_id = ? AND queue_date = ? " + priorityGate +
                        "ORDER BY doctor_id, priority DESC, token_number",
                mapper(), tenantId, Date.valueOf(date));
    }

    void updateStatus(UUID tenantId, UUID id, String status) {
        jdbc.update("UPDATE queue_entries SET status = ? WHERE tenant_id = ? AND id = ?", status, tenantId, id);
    }

    /** NB-143: (re-)flagging resets any earlier acknowledgement — a fresh flag needs a fresh
     * doctor ack; un-flagging (priority=false) clears the whole flag/ack history for a clean slate. */
    void updatePriority(UUID tenantId, UUID id, boolean priority, String reason, UUID flaggedBy) {
        if (priority) {
            jdbc.update("UPDATE queue_entries SET priority = true, priority_reason = ?, priority_flagged_by = ?, " +
                            "priority_flagged_at = now(), priority_acknowledged_by = NULL, priority_acknowledged_at = NULL " +
                            "WHERE tenant_id = ? AND id = ?",
                    reason, flaggedBy, tenantId, id);
        } else {
            jdbc.update("UPDATE queue_entries SET priority = false, priority_reason = NULL, priority_flagged_by = NULL, " +
                            "priority_flagged_at = NULL, priority_acknowledged_by = NULL, priority_acknowledged_at = NULL " +
                            "WHERE tenant_id = ? AND id = ?",
                    tenantId, id);
        }
    }

    void acknowledgePriority(UUID tenantId, UUID id, UUID acknowledgedBy) {
        jdbc.update("UPDATE queue_entries SET priority_acknowledged_by = ?, priority_acknowledged_at = now() " +
                "WHERE tenant_id = ? AND id = ?", acknowledgedBy, tenantId, id);
    }

    /**
     * NB-101: mean minutes from check-in to invoice (a real, already-recorded end-to-end visit
     * duration) over this doctor's most recent completed visits — a narrow direct query into
     * billing's invoices table rather than injecting CheckoutRepository, same convention as
     * CheckoutRepository's own reads into pharmacy/procedure_orders. OTC sales (no queue_entry_id)
     * are naturally excluded by the join. Empty when this doctor has no billed visits yet.
     */
    Optional<Double> averageRecentVisitMinutes(UUID tenantId, UUID doctorId, int sampleSize) {
        return jdbc.query(
                "SELECT AVG(minutes) AS avg_minutes FROM (" +
                        "  SELECT EXTRACT(EPOCH FROM (i.created_at - q.created_at)) / 60.0 AS minutes " +
                        "  FROM invoices i JOIN queue_entries q ON q.id = i.queue_entry_id " +
                        "  WHERE i.tenant_id = ? AND i.doctor_id = ? " +
                        "  ORDER BY i.created_at DESC LIMIT ?" +
                        ") recent",
                (rs, i) -> {
                    double v = rs.getDouble("avg_minutes");
                    return rs.wasNull() ? null : v;
                },
                tenantId, doctorId, sampleSize
        ).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    /** Still-in-the-pipeline entries for a doctor today — what a newly-arriving patient is actually behind. */
    int countActiveAhead(UUID tenantId, UUID doctorId, LocalDate date) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM queue_entries WHERE tenant_id = ? AND doctor_id = ? AND queue_date = ? " +
                        "AND status NOT IN ('completed', 'no_show', 'transferred_out')",
                Integer.class, tenantId, doctorId, Date.valueOf(date));
        return count == null ? 0 : count;
    }

    private RowMapper<QueueEntryRow> mapper() {
        return (rs, i) -> {
            String appointmentId = rs.getString("appointment_id");
            String parentQueueEntryId = rs.getString("parent_queue_entry_id");
            String flaggedBy = rs.getString("priority_flagged_by");
            String acknowledgedBy = rs.getString("priority_acknowledged_by");
            java.sql.Timestamp flaggedAt = rs.getTimestamp("priority_flagged_at");
            java.sql.Timestamp acknowledgedAt = rs.getTimestamp("priority_acknowledged_at");
            return new QueueEntryRow(
                    UUID.fromString(rs.getString("id")),
                    appointmentId == null ? null : UUID.fromString(appointmentId),
                    UUID.fromString(rs.getString("patient_id")),
                    UUID.fromString(rs.getString("doctor_id")),
                    UUID.fromString(rs.getString("department_id")),
                    parentQueueEntryId == null ? null : UUID.fromString(parentQueueEntryId),
                    rs.getDate("queue_date").toLocalDate(),
                    rs.getInt("token_number"),
                    rs.getString("status"),
                    rs.getBoolean("priority"),
                    rs.getString("priority_reason"),
                    flaggedBy == null ? null : UUID.fromString(flaggedBy),
                    flaggedAt == null ? null : flaggedAt.toInstant(),
                    acknowledgedBy == null ? null : UUID.fromString(acknowledgedBy),
                    acknowledgedAt == null ? null : acknowledgedAt.toInstant(),
                    rs.getString("source"),
                    rs.getTimestamp("created_at").toInstant());
        };
    }
}
