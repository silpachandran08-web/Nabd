package com.nabd.hms.clinical;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.clinical.NoteModels.NoteRow;
import static com.nabd.hms.clinical.NoteModels.QueueEntryOwner;

@Repository
class NoteRepository {

    private final JdbcTemplate jdbc;

    NoteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Not reaching into QueueRepository (different package) for one lookup — same call as
     * BillingRepository.planExists() reaching straight into the table it needs, nothing shared. */
    Optional<QueueEntryOwner> findQueueEntryOwner(UUID tenantId, UUID queueEntryId) {
        return jdbc.query("SELECT patient_id, doctor_id FROM queue_entries WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new QueueEntryOwner(UUID.fromString(rs.getString("patient_id")), UUID.fromString(rs.getString("doctor_id"))),
                tenantId, queueEntryId).stream().findFirst();
    }

    Optional<NoteRow> findByQueueEntry(UUID tenantId, UUID queueEntryId) {
        return jdbc.query("SELECT * FROM clinical_notes WHERE tenant_id = ? AND queue_entry_id = ?",
                mapper(), tenantId, queueEntryId).stream().findFirst();
    }

    /** Insert-or-update, but a no-op once signed — returns affected rows so the caller can tell. */
    int upsert(UUID tenantId, UUID queueEntryId, UUID patientId, UUID doctorId,
               String subjective, String objective, String assessment, String plan, String diagnosis) {
        return jdbc.update(
                "INSERT INTO clinical_notes (tenant_id, queue_entry_id, patient_id, doctor_id, subjective, objective, assessment, plan, diagnosis) " +
                        "VALUES (?,?,?,?,?,?,?,?,?) " +
                        "ON CONFLICT (queue_entry_id) DO UPDATE SET subjective = EXCLUDED.subjective, " +
                        "  objective = EXCLUDED.objective, assessment = EXCLUDED.assessment, plan = EXCLUDED.plan, " +
                        "  diagnosis = EXCLUDED.diagnosis " +
                        "  WHERE clinical_notes.status = 'draft'",
                tenantId, queueEntryId, patientId, doctorId, subjective, objective, assessment, plan, diagnosis);
    }

    /** Idempotent — signing an already-signed note is a no-op, not an error. */
    void sign(UUID tenantId, UUID queueEntryId) {
        jdbc.update("UPDATE clinical_notes SET status = 'signed', signed_at = now() " +
                "WHERE tenant_id = ? AND queue_entry_id = ? AND status = 'draft'", tenantId, queueEntryId);
    }

    private RowMapper<NoteRow> mapper() {
        return (rs, i) -> new NoteRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("queue_entry_id")),
                UUID.fromString(rs.getString("patient_id")),
                UUID.fromString(rs.getString("doctor_id")),
                rs.getString("subjective"),
                rs.getString("objective"),
                rs.getString("assessment"),
                rs.getString("plan"),
                rs.getString("diagnosis"),
                rs.getString("status"),
                rs.getTimestamp("signed_at") == null ? null : rs.getTimestamp("signed_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
