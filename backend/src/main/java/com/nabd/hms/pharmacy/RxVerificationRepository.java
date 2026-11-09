package com.nabd.hms.pharmacy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PHL-013: what a verified QR resolves to in this clinic, and the scan audit (rx_verifications). */
@Repository
class RxVerificationRepository {

    record Rx(UUID id, String status, UUID queueEntryId, Instant signedAt, String signerName, String signerRegistrationNo) {
    }

    record Line(UUID prescriptionItemId, String drugName, BigDecimal quantity, BigDecimal dispensed) {
    }

    /** The newest non-void invoice that billed a line of the prescription, and the clinic it was billed at. */
    record Dispense(Instant at, String invoiceNumber, String clinicName) {
    }

    private final JdbcTemplate jdbc;

    RxVerificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Rx> find(UUID tenantId, UUID prescriptionId) {
        return jdbc.query("SELECT p.id, p.status, p.queue_entry_id, p.signed_at, s.name, p.signed_by_registration_no " +
                        "FROM prescriptions p LEFT JOIN staff s ON s.id = p.signed_by WHERE p.tenant_id = ? AND p.id = ?",
                (rs, i) -> new Rx(rs.getObject("id", UUID.class), rs.getString("status"), rs.getObject("queue_entry_id", UUID.class),
                        rs.getTimestamp("signed_at") == null ? null : rs.getTimestamp("signed_at").toInstant(), rs.getString("name"),
                        rs.getString("signed_by_registration_no")), tenantId, prescriptionId).stream().findFirst();
    }

    /** Every line with what non-void invoices have billed against it, in prescription order. */
    List<Line> lines(UUID tenantId, UUID prescriptionId) {
        return jdbc.query("SELECT pi.id, pi.drug_name, pi.quantity, (SELECT COALESCE(sum(li.quantity), 0) FROM invoice_line_items li " +
                        "JOIN invoices i ON i.id = li.invoice_id AND i.tenant_id = li.tenant_id " +
                        "WHERE li.tenant_id = pi.tenant_id AND li.prescription_item_id = pi.id AND i.status <> 'void') AS dispensed " +
                        "FROM prescription_items pi WHERE pi.tenant_id = ? AND pi.prescription_id = ? ORDER BY pi.display_order",
                (rs, i) -> new Line(rs.getObject("id", UUID.class), rs.getString("drug_name"), rs.getBigDecimal("quantity"), rs.getBigDecimal("dispensed")),
                tenantId, prescriptionId);
    }

    Optional<Dispense> lastDispense(UUID tenantId, UUID prescriptionId) {
        return jdbc.query("SELECT i.created_at, i.invoice_number, t.name FROM invoices i JOIN tenants t ON t.id = i.tenant_id " +
                        "WHERE i.tenant_id = ? AND i.status <> 'void' AND EXISTS (SELECT 1 FROM invoice_line_items li JOIN prescription_items pi " +
                        "ON pi.id = li.prescription_item_id AND pi.tenant_id = li.tenant_id WHERE li.invoice_id = i.id AND li.tenant_id = i.tenant_id " +
                        "AND pi.prescription_id = ?) ORDER BY i.created_at DESC LIMIT 1",
                (rs, i) -> new Dispense(rs.getTimestamp("created_at").toInstant(), rs.getString("invoice_number"), rs.getString("name")),
                tenantId, prescriptionId).stream().findFirst();
    }

    Optional<UUID> worklistEntry(UUID tenantId, UUID prescriptionId) {
        return jdbc.query("SELECT id FROM pharmacy_worklist_entries WHERE tenant_id = ? AND prescription_id = ?",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId, prescriptionId).stream().findFirst();
    }

    void record(UUID tenantId, UUID prescriptionId, String result, String reason, UUID scannedBy) {
        jdbc.update("INSERT INTO rx_verifications (tenant_id, prescription_id, result, reason, scanned_by) VALUES (?, ?, ?, ?, ?)",
                tenantId, prescriptionId, result, reason, scannedBy);
    }
}
