package com.nabd.hms.pharmacy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** PHL-001: narrow direct reads of prescription lines and their fills (same precedent as CheckoutRepository.findSignedPrescriptionItems). */
@Repository
class RxGateRepository {

    private final JdbcTemplate jdbc;

    RxGateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Row-locks the prescription lines in id order (deadlock-free between two bills linking the same lines). */
    Map<UUID, RxGate.RxItemRow> lockItems(UUID tenantId, Collection<UUID> ids) {
        Map<UUID, RxGate.RxItemRow> out = new HashMap<>();
        jdbc.query("SELECT pi.id, pr.patient_id, pr.status, pi.quantity FROM prescription_items pi " +
                        "JOIN prescriptions pr ON pr.id = pi.prescription_id AND pr.tenant_id = pi.tenant_id " +
                        "WHERE pi.tenant_id = ? AND pi.id = ANY (?) ORDER BY pi.id FOR UPDATE OF pi",
                (RowCallbackHandler) rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    out.put(id, new RxGate.RxItemRow(id, rs.getObject("patient_id", UUID.class), rs.getString("status"), rs.getBigDecimal("quantity")));
                },
                tenantId, ids.toArray(new UUID[0]));
        return out;
    }

    /** Units already billed against each line on invoices that are not void (a void restocks, PHA-011). */
    Map<UUID, BigDecimal> dispensed(UUID tenantId, Collection<UUID> ids) {
        Map<UUID, BigDecimal> out = new HashMap<>();
        jdbc.query("SELECT li.prescription_item_id, SUM(li.quantity) AS qty FROM invoice_line_items li " +
                        "JOIN invoices i ON i.id = li.invoice_id AND i.tenant_id = li.tenant_id " +
                        "WHERE li.tenant_id = ? AND li.prescription_item_id = ANY (?) AND i.status <> 'void' GROUP BY li.prescription_item_id",
                (RowCallbackHandler) rs -> out.put(rs.getObject("prescription_item_id", UUID.class), rs.getBigDecimal("qty")),
                tenantId, ids.toArray(new UUID[0]));
        return out;
    }

    List<RxGate.RxLineResponse> searchByPhone(UUID tenantId, String digits) {
        return jdbc.query("SELECT pi.id, p.id AS patient_id, p.name AS patient_name, pi.drug_name, pi.dosage, pi.quantity, pr.signed_at, " +
                        "(SELECT COALESCE(SUM(li.quantity), 0) FROM invoice_line_items li JOIN invoices i ON i.id = li.invoice_id " +
                        "  WHERE li.tenant_id = pi.tenant_id AND li.prescription_item_id = pi.id AND i.status <> 'void') AS dispensed " +
                        "FROM patients p JOIN prescriptions pr ON pr.tenant_id = p.tenant_id AND pr.patient_id = p.id AND pr.status = 'signed' " +
                        "JOIN prescription_items pi ON pi.prescription_id = pr.id AND pi.tenant_id = pr.tenant_id " +
                        "WHERE p.tenant_id = ? AND right(regexp_replace(p.phone, '\\D', '', 'g'), 10) = right(?, 10) " +
                        "ORDER BY pr.signed_at DESC NULLS LAST, pi.display_order LIMIT 50",
                (rs, i) -> new RxGate.RxLineResponse(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getString("patient_name"), rs.getString("drug_name"), rs.getString("dosage"), rs.getBigDecimal("quantity"),
                        RxGate.remaining(rs.getBigDecimal("quantity"), rs.getBigDecimal("dispensed")),
                        rs.getTimestamp("signed_at") == null ? null : rs.getTimestamp("signed_at").toInstant()),
                tenantId, digits);
    }

    Optional<String[]> findActor(UUID tenantId, UUID staffId) {
        return jdbc.query("SELECT s.name, r.name AS role_name FROM staff s JOIN roles r ON r.id = s.role_id WHERE s.tenant_id = ? AND s.id = ?",
                (rs, i) -> new String[]{rs.getString("name"), rs.getString("role_name")}, tenantId, staffId).stream().findFirst();
    }
}
