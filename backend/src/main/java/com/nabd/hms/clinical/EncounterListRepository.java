package com.nabd.hms.clinical;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.nabd.hms.clinical.EncounterListModels.EncounterListItem;

@Repository
class EncounterListRepository {

    private final JdbcTemplate jdbc;

    EncounterListRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * DOC-005: a doctor's own visits that reached the consultation (in_consult / checkout_pending /
     * completed), with what each produced — all derived from existing tables: the note's status and
     * amendments, the prescription's status and line count, and clinical documents by status.
     * Filters on queue_date (the clinic's calendar day). Selects no clinical free text.
     */
    List<EncounterListItem> find(ReadScope scope, String state, LocalDate from, LocalDate to,
                                 int limit, Instant afterOccurredAt, UUID afterId) {
        StringBuilder sql = new StringBuilder("""
                SELECT q.id, q.patient_id, p.name AS patient_name, p.mrn AS patient_mrn, q.queue_date,
                       q.updated_at, q.status AS visit_status,
                       n.status AS note_status, n.signed_at AS note_signed_at,
                       (SELECT count(*) FROM note_amendments a WHERE a.note_id = n.id AND a.tenant_id = q.tenant_id) AS amendments,
                       rx.status AS rx_status,
                       (SELECT count(*) FROM prescription_items pi WHERE pi.prescription_id = rx.id AND pi.tenant_id = q.tenant_id) AS rx_items,
                       (SELECT count(*) FROM clinical_documents d WHERE d.queue_entry_id = q.id AND d.tenant_id = q.tenant_id AND d.status = 'draft') AS docs_draft,
                       (SELECT count(*) FROM clinical_documents d WHERE d.queue_entry_id = q.id AND d.tenant_id = q.tenant_id AND d.status = 'issued') AS docs_issued
                FROM queue_entries q
                JOIN patients p ON p.id = q.patient_id AND p.tenant_id = q.tenant_id
                LEFT JOIN clinical_notes n ON n.queue_entry_id = q.id AND n.tenant_id = q.tenant_id
                LEFT JOIN prescriptions rx ON rx.queue_entry_id = q.id AND rx.tenant_id = q.tenant_id
                WHERE q.tenant_id = ? AND q.doctor_id = ? AND q.status IN ('in_consult', 'checkout_pending', 'completed')
                """).append(scope.gate("q.patient_id", "q.tenant_id"));
        List<Object> params = new ArrayList<>(List.of(scope.tenantId(), scope.staffId()));
        if (from != null) {
            sql.append("AND q.queue_date >= ? ");
            params.add(Date.valueOf(from));
        }
        if (to != null) {
            sql.append("AND q.queue_date <= ? ");
            params.add(Date.valueOf(to));
        }
        if (state != null) {
            switch (state) {
                case "none" -> sql.append("AND n.id IS NULL ");
                case "draft" -> sql.append("AND n.status = 'draft' ");
                case "signed" -> sql.append("AND n.status = 'signed' ");
                case "unsigned" -> sql.append("AND (n.id IS NULL OR n.status = 'draft') ");
                default -> throw new IllegalArgumentException("state");
            }
        }
        if (afterOccurredAt != null) {
            sql.append("AND (q.updated_at, q.id) < (?, ?) ");
            params.add(Timestamp.from(afterOccurredAt));
            params.add(afterId);
        }
        sql.append("ORDER BY q.updated_at DESC, q.id DESC LIMIT ?");
        params.add(limit);

        return jdbc.query(sql.toString(), (rs, i) -> new EncounterListItem(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("patient_id")),
                rs.getString("patient_name"),
                rs.getString("patient_mrn"),
                rs.getDate("queue_date").toLocalDate(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("visit_status"),
                rs.getString("note_status") == null ? "none" : rs.getString("note_status"),
                rs.getTimestamp("note_signed_at") == null ? null : rs.getTimestamp("note_signed_at").toInstant(),
                rs.getInt("amendments"),
                rs.getString("rx_status") == null ? "none" : rs.getString("rx_status"),
                rs.getInt("rx_items"),
                rs.getInt("docs_draft"),
                rs.getInt("docs_issued")), params.toArray());
    }
}
