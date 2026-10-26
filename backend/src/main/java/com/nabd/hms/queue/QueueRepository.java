package com.nabd.hms.queue;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.queue.QueueModels.QueueEntryRow;

@Repository
class QueueRepository {

    // NUR-012: one joined read (patients, staff, appointments) so the worklist rows need no N+1.
    private static final String COLUMNS =
            "q.id, q.appointment_id, q.patient_id, q.doctor_id, q.department_id, q.parent_queue_entry_id, q.encounter_id, q.class, " +
                    "q.current_stage, q.workflow_definition_id, q.queue_date, q.token_number, " +
                    "q.status, q.priority, q.priority_reason, q.priority_flagged_by, q.priority_flagged_at, q.priority_acknowledged_by, " +
                    "q.priority_acknowledged_at, q.source, q.presenting_complaint, q.payment_mode, q.created_at, " +
                    "q.transfer_reason, q.transfer_reason_note, a.status AS appointment_status, " +
                    "date_part('year', age(q.queue_date, p.dob))::int AS age_years, p.gender, d.name AS doctor_name, " +
                    "GREATEST(0, floor(extract(epoch FROM (now() - q.created_at)) / 60))::int AS wait_minutes, " +
                    "CASE WHEN a.is_follow_up THEN 'follow_up' WHEN q.appointment_id IS NOT NULL THEN 'appointment' " +
                    "WHEN q.source = 'internal_transfer' THEN 'transfer' ELSE 'walk_in' END AS visit_type ";
    private static final String FROM =
            "FROM queue_entries q JOIN patients p ON p.id = q.patient_id JOIN staff d ON d.id = q.doctor_id " +
                    "LEFT JOIN appointments a ON a.id = q.appointment_id ";

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
    void lockDoctorDay(UUID tenantId, UUID doctorId, LocalDate date) {
        // T-201: two-key lock (hash(tenant:doctor), clinic epoch day) instead of one 32-bit hash of the whole string
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?), ?)", Object.class, tenantId + ":" + doctorId, (int) date.toEpochDay());
    }

    int nextTokenNumber(UUID tenantId, UUID doctorId, LocalDate date) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(token_number), 0) FROM queue_entries WHERE tenant_id = ? AND doctor_id = ? AND queue_date = ?",
                Integer.class, tenantId, doctorId, Date.valueOf(date));
        return (max == null ? 0 : max) + 1;
    }

    /** encounterId is null for a fresh check-in (the row's own id becomes its encounter_id via
     * queue_entries_default_encounter, see V43) and the parent leg's encounterId when opened by
     * transfer() — propagating one visit's identity across however many department legs it takes. */
    UUID insert(UUID tenantId, UUID appointmentId, UUID patientId, UUID doctorId, UUID departmentId,
                UUID parentQueueEntryId, UUID encounterId, LocalDate queueDate, int tokenNumber, String source, String status,
                String presentingComplaint, String paymentMode, String transferReason, String transferReasonNote, Instant createdAt) {
        UUID id = com.nabd.hms.shared.UuidV7.next();
        jdbc.update("INSERT INTO queue_entries (id, tenant_id, appointment_id, patient_id, doctor_id, department_id, " +
                        "parent_queue_entry_id, encounter_id, queue_date, token_number, source, status, presenting_complaint, payment_mode, transfer_reason, transfer_reason_note, created_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", // PLT-028: created_at from the injected clock, so session-time checks see the same "now"
                id, tenantId, appointmentId, patientId, doctorId, departmentId, parentQueueEntryId, encounterId,
                Date.valueOf(queueDate), tokenNumber, source, status, presentingComplaint, paymentMode, transferReason, transferReasonNote, Timestamp.from(createdAt));
        return id;
    }

    boolean patientExists(UUID tenantId, UUID patientId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM patients WHERE tenant_id = ? AND id = ?)", Boolean.class, tenantId, patientId));
    }

    /** The live check-in of an appointment: {tokenNumber, queueEntryId, checkedInAt}; empty if none. */
    Optional<java.util.Map<String, Object>> findCheckInOf(UUID tenantId, UUID appointmentId) {
        return jdbc.query("SELECT id, token_number, created_at FROM queue_entries " +
                        "WHERE tenant_id = ? AND appointment_id = ? AND NOT legacy_duplicate",
                (rs, i) -> java.util.Map.<String, Object>of("queueEntryId", rs.getString("id"),
                        "tokenNumber", rs.getInt("token_number"), "checkedInAt", rs.getTimestamp("created_at").toInstant().toString()),
                tenantId, appointmentId).stream().findFirst();
    }

    /** Encounter-wide lookup: a transferred leg has no appointment of its own, its first leg does. */
    Optional<UUID> appointmentOfEncounter(UUID tenantId, UUID encounterId) {
        return jdbc.query("SELECT appointment_id FROM queue_entries WHERE tenant_id = ? AND encounter_id = ? AND appointment_id IS NOT NULL LIMIT 1",
                (rs, i) -> UUID.fromString(rs.getString(1)), tenantId, encounterId).stream().findFirst();
    }

    boolean staffExists(UUID tenantId, UUID staffId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM staff WHERE tenant_id = ? AND id = ?)", Boolean.class, tenantId, staffId));
    }

    /** Server-derived, never client-supplied — a doctor's own assigned department, falling back to
     * the tenant's single default department if nobody has assigned one yet (so check-in never
     * breaks for a doctor no one has gotten around to configuring). */
    /** Empty list = no such staff row (one round trip also answers "does the doctor exist"); the element may be null. */
    List<UUID> findCheckInDepartment(UUID tenantId, UUID doctorId) {
        return jdbc.query(
                "SELECT COALESCE(s.department_id, (SELECT id FROM departments WHERE tenant_id = ? AND is_default)) " +
                        "FROM staff s WHERE s.tenant_id = ? AND s.id = ?",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId, tenantId, doctorId);
    }

    /** The owner-designed transfer graph, read narrowly here rather than injecting DepartmentRepository. */
    boolean transferAllowed(UUID tenantId, UUID fromDepartmentId, UUID toDepartmentId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM department_transfers WHERE tenant_id = ? AND from_department_id = ? AND to_department_id = ?)",
                Boolean.class, tenantId, fromDepartmentId, toDepartmentId);
        return Boolean.TRUE.equals(exists);
    }

    Optional<QueueEntryRow> findById(UUID tenantId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + FROM + "WHERE q.tenant_id = ? AND q.id = ?",
                mapper(), tenantId, id).stream().findFirst();
    }

    List<QueueEntryRow> listForDay(UUID tenantId, UUID doctorId, UUID departmentId, LocalDate date, boolean priorityOnly) {
        String priorityGate = priorityOnly ? "AND q.priority = true " : "";
        if (doctorId != null) {
            return jdbc.query("SELECT " + COLUMNS + FROM +
                            "WHERE q.tenant_id = ? AND q.doctor_id = ? AND q.queue_date = ? " + priorityGate +
                            "ORDER BY q.priority DESC, q.token_number",
                    mapper(), tenantId, doctorId, Date.valueOf(date));
        }
        if (departmentId != null) {
            return jdbc.query("SELECT " + COLUMNS + FROM +
                            "WHERE q.tenant_id = ? AND q.department_id = ? AND q.queue_date = ? " + priorityGate +
                            "ORDER BY q.doctor_id, q.priority DESC, q.token_number",
                    mapper(), tenantId, departmentId, Date.valueOf(date));
        }
        return jdbc.query("SELECT " + COLUMNS + FROM +
                        "WHERE q.tenant_id = ? AND q.queue_date = ? " + priorityGate +
                        "ORDER BY q.doctor_id, q.priority DESC, q.token_number",
                mapper(), tenantId, Date.valueOf(date));
    }

    /** Compare-and-set (ACC-002): returns 0 when the row is no longer in {@code expected}. */
    int updateStatus(UUID tenantId, UUID id, String expected, String status) {
        return jdbc.update("UPDATE queue_entries SET status = ? WHERE tenant_id = ? AND id = ? AND status = ?", status, tenantId, id, expected);
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
            String workflowDefinitionId = rs.getString("workflow_definition_id");
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
                    UUID.fromString(rs.getString("encounter_id")),
                    rs.getString("class"),
                    rs.getString("current_stage"),
                    workflowDefinitionId == null ? null : UUID.fromString(workflowDefinitionId),
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
                    rs.getString("presenting_complaint"),
                    rs.getString("payment_mode"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getString("transfer_reason"), rs.getString("transfer_reason_note"),
                    (Integer) rs.getObject("age_years"), rs.getString("gender"), rs.getString("doctor_name"),
                    rs.getInt("wait_minutes"), rs.getString("visit_type"), rs.getString("appointment_status"));
        };
    }
}
