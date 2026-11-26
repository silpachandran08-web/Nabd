package com.nabd.hms.clinical;

import com.nabd.hms.clinical.api.SignedConsults;
import com.nabd.hms.common.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
class SignedConsultsJdbc implements SignedConsults {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    SignedConsultsJdbc(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @Override
    public Optional<Consult> forVisit(UUID queueEntryId) {
        UUID t = tenant.current();
        return jdbc.query("SELECT id, patient_id, COALESCE(signed_by, doctor_id) AS author_id, signed_at, subjective, objective, assessment, plan, diagnosis "
                        + "FROM clinical_notes WHERE tenant_id = ? AND queue_entry_id = ? AND status = 'signed'",
                (rs, i) -> new Note(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class), rs.getObject("author_id", UUID.class),
                        rs.getTimestamp("signed_at") == null ? null : rs.getTimestamp("signed_at").toInstant(), rs.getString("subjective"),
                        rs.getString("objective"), rs.getString("assessment"), rs.getString("plan"), rs.getString("diagnosis"), List.of()),
                t, queueEntryId).stream().findFirst().map(n -> new Consult(new Note(n.id(), n.patientId(), n.authorId(), n.signedAt(), n.subjective(),
                n.objective(), n.assessment(), n.plan(), n.diagnosis(), amendments(t, n.id())), prescription(t, queueEntryId)));
    }

    private List<Amendment> amendments(UUID t, UUID noteId) {
        return jdbc.query("SELECT created_at, amended_by, reason, subjective, objective, assessment, plan, diagnosis FROM note_amendments "
                        + "WHERE tenant_id = ? AND note_id = ? ORDER BY created_at, id",
                (rs, i) -> new Amendment(rs.getTimestamp("created_at").toInstant(), rs.getObject("amended_by", UUID.class), rs.getString("reason"),
                        rs.getString("subjective"), rs.getString("objective"), rs.getString("assessment"), rs.getString("plan"), rs.getString("diagnosis")),
                t, noteId);
    }

    private Prescription prescription(UUID t, UUID queueEntryId) {
        List<RxLine> lines = jdbc.query("SELECT pi.id, pi.drug_name, pi.brand_name, pi.dosage, pi.sig_pattern, pi.frequency, pi.duration, pi.route, "
                        + "pi.instructions, pi.quantity, pi.quantity_unit FROM prescription_items pi "
                        + "JOIN prescriptions p ON p.tenant_id = pi.tenant_id AND p.id = pi.prescription_id "
                        + "WHERE p.tenant_id = ? AND p.queue_entry_id = ? AND p.status = 'signed' ORDER BY pi.display_order, pi.id",
                (rs, i) -> new RxLine(rs.getObject("id", UUID.class), rs.getString("drug_name"), rs.getString("brand_name"), rs.getString("dosage"),
                        rs.getString("sig_pattern"), rs.getString("frequency"), rs.getString("duration"), rs.getString("route"),
                        rs.getString("instructions"), rs.getBigDecimal("quantity"), rs.getString("quantity_unit")),
                t, queueEntryId);
        return jdbc.query("SELECT COALESCE(signed_by, doctor_id) AS prescriber_id, signed_at FROM prescriptions WHERE tenant_id = ? AND queue_entry_id = ? AND status = 'signed'",
                (rs, i) -> new Prescription(rs.getObject("prescriber_id", UUID.class),
                        rs.getTimestamp("signed_at") == null ? null : rs.getTimestamp("signed_at").toInstant(), lines),
                t, queueEntryId).stream().findFirst().orElse(null);
    }
}
