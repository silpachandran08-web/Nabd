package com.nabd.hms.clinical;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

import static com.nabd.hms.clinical.TimelineModels.EncounterRow;

@Repository
class TimelineRepository {

    private final JdbcTemplate jdbc;

    TimelineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** NB-115: one row per completed visit, newest first — diagnosis/assessment from that visit's
     * note (if any), medications aggregated from its signed prescription (if any). All existing
     * data, no new table. */
    List<EncounterRow> findForPatient(UUID tenantId, UUID patientId) {
        return jdbc.query("""
                SELECT q.id AS queue_entry_id, q.updated_at AS occurred_at, q.doctor_id,
                       n.diagnosis, n.assessment,
                       string_agg(DISTINCT pi.drug_name, ', ') AS medications
                FROM queue_entries q
                LEFT JOIN clinical_notes n ON n.queue_entry_id = q.id AND n.tenant_id = q.tenant_id
                LEFT JOIN prescriptions p ON p.queue_entry_id = q.id AND p.tenant_id = q.tenant_id AND p.status = 'signed'
                LEFT JOIN prescription_items pi ON pi.prescription_id = p.id AND pi.tenant_id = q.tenant_id
                WHERE q.tenant_id = ? AND q.patient_id = ? AND q.status = 'completed'
                GROUP BY q.id, q.updated_at, q.doctor_id, n.diagnosis, n.assessment
                ORDER BY q.updated_at DESC
                """,
                (rs, i) -> new EncounterRow(
                        UUID.fromString(rs.getString("queue_entry_id")),
                        rs.getTimestamp("occurred_at").toInstant(),
                        UUID.fromString(rs.getString("doctor_id")),
                        rs.getString("diagnosis"),
                        rs.getString("assessment"),
                        rs.getString("medications")),
                tenantId, patientId);
    }
}
