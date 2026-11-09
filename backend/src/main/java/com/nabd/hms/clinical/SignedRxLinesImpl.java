package com.nabd.hms.clinical;

import com.nabd.hms.clinical.api.SignedRxLines;
import com.nabd.hms.common.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(propagation = Propagation.MANDATORY)
class SignedRxLinesImpl implements SignedRxLines {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    SignedRxLinesImpl(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @Override
    public List<Line> forVisit(UUID queueEntryId) {
        return jdbc.query("SELECT pi.id, pi.drug_id, pi.drug_name, pi.quantity FROM prescription_items pi " +
                        "JOIN prescriptions pr ON pr.id = pi.prescription_id AND pr.tenant_id = pi.tenant_id " +
                        "WHERE pr.tenant_id = ? AND pr.queue_entry_id = ? AND pr.status = 'signed' ORDER BY pr.signed_at, pi.display_order",
                (rs, i) -> new Line(rs.getObject("id", UUID.class), rs.getString("drug_id"), rs.getString("drug_name"), rs.getBigDecimal("quantity")),
                tenant.current(), queueEntryId);
    }

    @Override
    public java.util.Optional<Signed> signed(UUID queueEntryId) {
        return jdbc.query("SELECT id, patient_id, doctor_id, signed_at FROM prescriptions WHERE tenant_id = ? AND queue_entry_id = ? AND status = 'signed'",
                (rs, i) -> new Signed(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class), rs.getObject("doctor_id", UUID.class),
                        rs.getTimestamp("signed_at").toInstant()),
                tenant.current(), queueEntryId).stream().findFirst();
    }
}
