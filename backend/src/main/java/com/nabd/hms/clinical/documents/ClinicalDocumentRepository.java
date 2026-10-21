package com.nabd.hms.clinical.documents;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.clinical.documents.ClinicalDocumentModels.DocumentRow;
import static com.nabd.hms.clinical.documents.ClinicalDocumentModels.PartyInfo;
import static com.nabd.hms.clinical.documents.ClinicalDocumentModels.PatientHeader;
import static com.nabd.hms.clinical.documents.ClinicalDocumentModels.SummarySources;
import static com.nabd.hms.clinical.documents.ClinicalDocumentModels.VisitInfo;

@Repository
class ClinicalDocumentRepository {

    private final JdbcTemplate jdbc;

    ClinicalDocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<VisitInfo> findVisit(UUID tenantId, UUID queueEntryId) {
        return jdbc.query("SELECT patient_id, doctor_id, queue_date::text AS queue_date FROM queue_entries WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new VisitInfo(UUID.fromString(rs.getString("patient_id")),
                        UUID.fromString(rs.getString("doctor_id")), rs.getString("queue_date")),
                tenantId, queueEntryId).stream().findFirst();
    }

    UUID insert(UUID tenantId, VisitInfo visit, UUID queueEntryId, String type, String bodyJson, UUID createdBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO clinical_documents (id, tenant_id, patient_id, queue_entry_id, doctor_id, doc_type, body, created_by) " +
                        "VALUES (?,?,?,?,?,?,?::jsonb,?)",
                id, tenantId, visit.patientId(), queueEntryId, visit.doctorId(), type, bodyJson, createdBy);
        return id;
    }

    List<DocumentRow> listForVisit(UUID tenantId, UUID queueEntryId) {
        return jdbc.query("SELECT *, body::text AS body_json, header::text AS header_json FROM clinical_documents " +
                        "WHERE tenant_id = ? AND queue_entry_id = ? ORDER BY created_at, id",
                mapper(), tenantId, queueEntryId);
    }

    Optional<DocumentRow> find(UUID tenantId, UUID id) {
        return jdbc.query("SELECT *, body::text AS body_json, header::text AS header_json FROM clinical_documents " +
                "WHERE tenant_id = ? AND id = ?", mapper(), tenantId, id).stream().findFirst();
    }

    /** Row lock held to the end of the transaction: serialises edit/issue on one document. */
    Optional<DocumentRow> lock(UUID tenantId, UUID id) {
        return jdbc.query("SELECT *, body::text AS body_json, header::text AS header_json FROM clinical_documents " +
                "WHERE tenant_id = ? AND id = ? FOR UPDATE", mapper(), tenantId, id).stream().findFirst();
    }

    /** No-op (returns 0) once issued. */
    int updateDraftBody(UUID tenantId, UUID id, String bodyJson) {
        return jdbc.update("UPDATE clinical_documents SET body = ?::jsonb WHERE tenant_id = ? AND id = ? AND status = 'draft'",
                bodyJson, tenantId, id);
    }

    int issue(UUID tenantId, UUID id, UUID issuedBy, String headerJson) {
        return jdbc.update("UPDATE clinical_documents SET status = 'issued', header = ?::jsonb, issued_by = ?, issued_at = now() " +
                "WHERE tenant_id = ? AND id = ? AND status = 'draft'", headerJson, issuedBy, tenantId, id);
    }

    // ── header snapshot sources ──────────────────────────────────────────

    Optional<String> findClinicName(UUID tenantId) {
        return jdbc.query("SELECT name FROM tenants WHERE id = ?", (rs, i) -> rs.getString(1), tenantId).stream().findFirst();
    }

    Optional<String> findRegion(UUID tenantId) {
        return jdbc.query("SELECT region FROM tenants WHERE id = ?", (rs, i) -> rs.getString(1), tenantId).stream().findFirst();
    }

    Optional<PatientHeader> findPatientHeader(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT name, mrn, dob::text AS dob, gender FROM patients WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new PatientHeader(rs.getString("name"), rs.getString("mrn"), rs.getString("dob"), rs.getString("gender")),
                tenantId, patientId).stream().findFirst();
    }

    /** The doctor's printed professional registration number (V55); empty when none is recorded. */
    Optional<String> findRegistrationNo(UUID tenantId, UUID staffId) {
        return jdbc.query("SELECT professional_registration_no FROM staff WHERE tenant_id = ? AND id = ?",
                (rs, i) -> rs.getString("professional_registration_no"),
                tenantId, staffId).stream().filter(v -> v != null && !v.isBlank()).findFirst();
    }

    // ── visit summary prefill: reads the visit's own signed note / prescription ──

    SummarySources findSummarySources(UUID tenantId, UUID queueEntryId) {
        var note = jdbc.query("SELECT status, diagnosis, assessment, plan FROM clinical_notes WHERE tenant_id = ? AND queue_entry_id = ?",
                (rs, i) -> new String[]{rs.getString("status"), rs.getString("diagnosis"), rs.getString("assessment"), rs.getString("plan")},
                tenantId, queueEntryId).stream().findFirst();
        boolean noteSigned = note.isPresent() && "signed".equals(note.get()[0]);

        var rxStatus = jdbc.query("SELECT id, status FROM prescriptions WHERE tenant_id = ? AND queue_entry_id = ?",
                (rs, i) -> new String[]{rs.getString("id"), rs.getString("status")}, tenantId, queueEntryId).stream().findFirst();
        boolean rxSigned = rxStatus.isPresent() && "signed".equals(rxStatus.get()[1]);
        List<String> meds = new ArrayList<>();
        if (rxSigned) {
            meds = jdbc.query("SELECT drug_name, dosage, frequency, duration, instructions FROM prescription_items " +
                            "WHERE tenant_id = ? AND prescription_id = ?::uuid ORDER BY display_order",
                    (rs, i) -> joinNonBlank(" · ", rs.getString("drug_name"), rs.getString("dosage"),
                            rs.getString("frequency"), rs.getString("duration"), rs.getString("instructions")),
                    tenantId, rxStatus.get()[0]);
        }
        return new SummarySources(noteSigned,
                noteSigned ? note.get()[1] : null, noteSigned ? note.get()[2] : null, noteSigned ? note.get()[3] : null,
                rxSigned, meds);
    }

    private static String joinNonBlank(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(sep);
                }
                sb.append(p.strip());
            }
        }
        return sb.toString();
    }

    private RowMapper<DocumentRow> mapper() {
        return (rs, i) -> new DocumentRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("patient_id")),
                UUID.fromString(rs.getString("queue_entry_id")),
                UUID.fromString(rs.getString("doctor_id")),
                rs.getString("doc_type"),
                rs.getString("status"),
                rs.getString("body_json"),
                rs.getString("header_json"),
                UUID.fromString(rs.getString("created_by")),
                rs.getString("issued_by") == null ? null : UUID.fromString(rs.getString("issued_by")),
                rs.getTimestamp("issued_at") == null ? null : rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
