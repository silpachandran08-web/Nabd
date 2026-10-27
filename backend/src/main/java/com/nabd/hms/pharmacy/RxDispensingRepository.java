package com.nabd.hms.pharmacy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** PHL-018: dispenses and stock_discrepancies. Neither touches stock_qty. */
@Repository
class RxDispensingRepository {

    private final JdbcTemplate jdbc;

    RxDispensingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    UUID insertDispense(UUID tenantId, UUID invoiceId, String linesJson, UUID actorId) {
        return jdbc.queryForObject("INSERT INTO dispenses (tenant_id, invoice_id, source, lines, created_by) VALUES (?,?,'nabd_rx',?::jsonb,?) RETURNING id",
                UUID.class, tenantId, invoiceId, linesJson, actorId);
    }

    int shortfall(UUID tenantId, UUID invoiceId, UUID itemId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(sd.qty), 0) FROM stock_discrepancies sd JOIN dispenses d ON d.id = sd.dispense_id " +
                "AND d.tenant_id = sd.tenant_id WHERE sd.tenant_id = ? AND d.invoice_id = ? AND sd.item_id = ?", Integer.class, tenantId, invoiceId, itemId);
    }

    void insertDiscrepancy(UUID tenantId, UUID itemId, int qty, String reason, UUID dispenseId, UUID actorId) {
        jdbc.update("INSERT INTO stock_discrepancies (tenant_id, item_id, qty, reason, dispense_id, created_by) VALUES (?,?,?,?,?,?)",
                tenantId, itemId, qty, reason, dispenseId, actorId);
    }
}
