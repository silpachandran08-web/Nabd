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
            "id, appointment_id, patient_id, doctor_id, queue_date, token_number, status, priority, priority_reason, created_at ";

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

    UUID insert(UUID tenantId, UUID appointmentId, UUID patientId, UUID doctorId, LocalDate queueDate, int tokenNumber) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO queue_entries (id, tenant_id, appointment_id, patient_id, doctor_id, queue_date, token_number) " +
                        "VALUES (?,?,?,?,?,?,?)",
                id, tenantId, appointmentId, patientId, doctorId, Date.valueOf(queueDate), tokenNumber);
        return id;
    }

    Optional<QueueEntryRow> findById(UUID tenantId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + "FROM queue_entries WHERE tenant_id = ? AND id = ?",
                mapper(), tenantId, id).stream().findFirst();
    }

    List<QueueEntryRow> listForDay(UUID tenantId, UUID doctorId, LocalDate date) {
        if (doctorId != null) {
            return jdbc.query("SELECT " + COLUMNS + "FROM queue_entries " +
                            "WHERE tenant_id = ? AND doctor_id = ? AND queue_date = ? " +
                            "ORDER BY priority DESC, token_number",
                    mapper(), tenantId, doctorId, Date.valueOf(date));
        }
        return jdbc.query("SELECT " + COLUMNS + "FROM queue_entries " +
                        "WHERE tenant_id = ? AND queue_date = ? ORDER BY doctor_id, priority DESC, token_number",
                mapper(), tenantId, Date.valueOf(date));
    }

    void updateStatus(UUID tenantId, UUID id, String status) {
        jdbc.update("UPDATE queue_entries SET status = ? WHERE tenant_id = ? AND id = ?", status, tenantId, id);
    }

    void updatePriority(UUID tenantId, UUID id, boolean priority, String reason) {
        jdbc.update("UPDATE queue_entries SET priority = ?, priority_reason = ? WHERE tenant_id = ? AND id = ?",
                priority, reason, tenantId, id);
    }

    private RowMapper<QueueEntryRow> mapper() {
        return (rs, i) -> {
            String appointmentId = rs.getString("appointment_id");
            return new QueueEntryRow(
                    UUID.fromString(rs.getString("id")),
                    appointmentId == null ? null : UUID.fromString(appointmentId),
                    UUID.fromString(rs.getString("patient_id")),
                    UUID.fromString(rs.getString("doctor_id")),
                    rs.getDate("queue_date").toLocalDate(),
                    rs.getInt("token_number"),
                    rs.getString("status"),
                    rs.getBoolean("priority"),
                    rs.getString("priority_reason"),
                    rs.getTimestamp("created_at").toInstant());
        };
    }
}
