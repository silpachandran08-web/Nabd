package com.nabd.hms.clinical;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.nabd.hms.clinical.TimelineModels.EncounterRow;

@Repository
class TimelineRepository {

    private final JdbcTemplate jdbc;

    TimelineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** NB-115: one row per completed visit, newest first, capped to a 5-year window — diagnosis/
     * assessment from that visit's note (if any), medications aggregated from its signed
     * prescription (if any). All existing data, no new table.
     * ADM-013: with {@code imported}, past visits brought from the old software (issued 'imported_history' documents) are
     * merged in by their visit date (midnight in the clinic's zone). They are outside the 5-year window on purpose: the
     * old history is exactly what the owner asked to keep. */
    List<EncounterRow> findForPatient(UUID tenantId, UUID patientId, Instant since, int limit,
                                       Instant afterOccurredAt, UUID afterId, boolean imported, String zoneId) {
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM (
                SELECT q.id AS id, q.updated_at AS occurred_at, q.doctor_id,
                       n.diagnosis, n.assessment,
                       string_agg(DISTINCT pi.drug_name, ', ') AS medications,
                       NULL::text AS kind, NULL::text AS doctor_name, NULL::text AS bill
                FROM queue_entries q
                LEFT JOIN clinical_notes n ON n.queue_entry_id = q.id AND n.tenant_id = q.tenant_id
                LEFT JOIN prescriptions p ON p.queue_entry_id = q.id AND p.tenant_id = q.tenant_id AND p.status = 'signed'
                LEFT JOIN prescription_items pi ON pi.prescription_id = p.id AND pi.tenant_id = q.tenant_id
                WHERE q.tenant_id = ? AND q.patient_id = ? AND q.status = 'completed' AND q.updated_at >= ?
                GROUP BY q.id, q.updated_at, q.doctor_id, n.diagnosis, n.assessment
                """);
        List<Object> params = new ArrayList<>(List.of(tenantId, patientId, Timestamp.from(since)));
        if (imported) {
            sql.append("""
                    UNION ALL
                    SELECT d.id, (d.body->>'visitDate')::date::timestamp AT TIME ZONE ?::text, NULL::uuid,
                           d.body->>'diagnosis', NULLIF(concat_ws(E'\\n', d.body->>'complaint', d.body->>'notes'), ''),
                           d.body->>'medications', 'imported_history', d.body->>'doctorName',
                           NULLIF(concat_ws(' ', d.body->>'billNo', d.body->>'billAmount'), '')
                    FROM clinical_documents d
                    WHERE d.tenant_id = ? AND d.patient_id = ? AND d.doc_type = 'imported_history'
                    """);
            params.addAll(List.of(zoneId, tenantId, patientId));
        }
        sql.append(") t ");
        if (afterOccurredAt != null) {
            sql.append("WHERE (occurred_at, id) < (?, ?) ");
            params.add(Timestamp.from(afterOccurredAt));
            params.add(afterId);
        }
        sql.append("ORDER BY occurred_at DESC, id DESC LIMIT ?");
        params.add(limit);

        return jdbc.query(sql.toString(),
                (rs, i) -> new EncounterRow(
                        UUID.fromString(rs.getString("id")),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("doctor_id") == null ? null : UUID.fromString(rs.getString("doctor_id")),
                        rs.getString("diagnosis"),
                        rs.getString("assessment"),
                        rs.getString("medications"),
                        rs.getString("kind"),
                        rs.getString("doctor_name"),
                        rs.getString("bill")),
                params.toArray());
    }
}
