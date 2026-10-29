package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.VisitSummaryResponse.VisitIntake;
import com.nabd.hms.clinical.dto.VisitSummaryResponse.VisitMedicine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** CLN-008: the summary's own reads (visit header, medicines of the last prescriptions, attachment count). Read-only. */
@Repository
class VisitSummaryRepository {

    record VisitHead(UUID patientId, String name, LocalDate dob, String gender, String mrn, String queueStatus, VisitIntake intake) {
    }

    private final JdbcTemplate jdbc;

    VisitSummaryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<VisitHead> findHead(UUID tenantId, UUID queueEntryId) {
        return jdbc.query("SELECT q.patient_id, p.name, p.dob, p.gender, p.mrn, q.status, q.presenting_complaint, q.payment_mode "
                        + "FROM queue_entries q JOIN patients p ON p.tenant_id = q.tenant_id AND p.id = q.patient_id "
                        + "WHERE q.tenant_id = ? AND q.id = ?",
                (rs, i) -> new VisitHead(rs.getObject("patient_id", UUID.class), rs.getString("name"),
                        rs.getObject("dob", LocalDate.class), rs.getString("gender"), rs.getString("mrn"), rs.getString("status"),
                        new VisitIntake(rs.getString("presenting_complaint"), rs.getString("payment_mode"))),
                tenantId, queueEntryId).stream().findFirst();
    }

    /** Items of the patient's last 3 signed prescriptions, newest first (served by idx_prescriptions_patient + the item index). */
    List<VisitMedicine> recentMedicines(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT i.drug_name, i.dosage, i.frequency, i.duration FROM prescription_items i "
                        + "JOIN (SELECT id, signed_at FROM prescriptions WHERE tenant_id = ? AND patient_id = ? AND status = 'signed' "
                        + "      ORDER BY signed_at DESC LIMIT 3) r ON r.id = i.prescription_id "
                        + "WHERE i.tenant_id = ? ORDER BY r.signed_at DESC, i.display_order LIMIT 60",
                (rs, i) -> new VisitMedicine(rs.getString("drug_name"), rs.getString("dosage"), rs.getString("frequency"), rs.getString("duration")),
                tenantId, patientId, tenantId);
    }

    int attachmentCount(UUID tenantId, UUID queueEntryId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM visit_attachments WHERE tenant_id = ? AND visit_id = ?", Integer.class, tenantId, queueEntryId);
        return n == null ? 0 : n;
    }
}
