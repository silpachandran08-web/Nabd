package com.nabd.hms.pharmacy;

import com.nabd.hms.pharmacy.dto.QuarantineDtos.DocumentLine;
import com.nabd.hms.pharmacy.dto.QuarantineDtos.SupplierReturn;
import com.nabd.hms.pharmacy.dto.QuarantineDtos.Valuation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PHL-009: reads of quarantined stock and the return / write-off documents. Stock itself is never written here (StockCommands does that). */
@Repository
class QuarantineRepository {

    /** registerType: the statutory register of the item's class (non-null = controlled). */
    record BatchRow(UUID batchId, UUID itemId, String itemName, String batchNo, LocalDate expiryDate, int qtyOnHand, BigDecimal purchaseRate,
                    UUID supplierId, String registerType, String status) {
    }

    private static final String BATCH = """
            SELECT b.id, b.item_id, c.name, b.batch_no, b.expiry_date, b.qty_on_hand, b.purchase_rate, b.supplier_id, b.status,
                   (SELECT max(r.register_type) FROM pharmacy_item_profiles p JOIN pharmacy_schedule_rules r ON r.schedule_class = p.schedule_class
                     WHERE p.tenant_id = b.tenant_id AND p.item_id = b.item_id) AS register_type
              FROM stock_batches b JOIN charge_catalogue c ON c.id = b.item_id AND c.tenant_id = b.tenant_id
            """;

    private static final RowMapper<BatchRow> BATCH_MAP = (rs, i) -> new BatchRow(rs.getObject("id", UUID.class), rs.getObject("item_id", UUID.class),
            rs.getString("name"), rs.getString("batch_no"), rs.getObject("expiry_date", LocalDate.class), rs.getInt("qty_on_hand"),
            rs.getBigDecimal("purchase_rate"), rs.getObject("supplier_id", UUID.class), rs.getString("register_type"), rs.getString("status"));

    private final JdbcTemplate jdbc;

    QuarantineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Quarantined batches that still hold stock, by item name then expiry. */
    List<BatchRow> quarantined(UUID tenantId) {
        // shortcut: one page of 500; add keyset paging if a clinic ever holds more batches in quarantine
        return jdbc.query(BATCH + " WHERE b.tenant_id = ? AND b.status = 'quarantined' AND b.qty_on_hand > 0 " +
                "ORDER BY lower(c.name), b.expiry_date NULLS LAST, b.id LIMIT 500", BATCH_MAP, tenantId);
    }

    List<BatchRow> byIds(UUID tenantId, UUID[] batchIds) {
        return jdbc.query(con -> {
            var ps = con.prepareStatement(BATCH + " WHERE b.tenant_id = ? AND b.id = ANY (?)");
            ps.setObject(1, tenantId);
            ps.setArray(2, con.createArrayOf("uuid", batchIds));
            return ps;
        }, BATCH_MAP);
    }

    Valuation valuation(UUID tenantId, LocalDate today) {
        return jdbc.queryForObject("""
                SELECT round(COALESCE(sum(v) FILTER (WHERE saleable), 0), 2), round(COALESCE(sum(v) FILTER (WHERE NOT saleable), 0), 2),
                       count(*) FILTER (WHERE saleable), count(*) FILTER (WHERE NOT saleable)
                  FROM (SELECT qty_on_hand * COALESCE(purchase_rate, 0) AS v,
                               (status = 'active' AND (expiry_date IS NULL OR expiry_date >= ?)) AS saleable
                          FROM stock_batches WHERE tenant_id = ? AND qty_on_hand > 0) x""",
                (rs, i) -> new Valuation(today, rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(1).add(rs.getBigDecimal(2)),
                        rs.getInt(3), rs.getInt(4)), Date.valueOf(today), tenantId);
    }

    void insertReturn(UUID tenantId, UUID id, UUID supplierId, String supplierName, String debitNoteNumber, BigDecimal value, String reason,
                      UUID createdBy) {
        jdbc.update("INSERT INTO supplier_returns (id, tenant_id, supplier_id, supplier_name_snapshot, debit_note_number, value, reason, created_by) " +
                "VALUES (?,?,?,?,?,?,?,?)", id, tenantId, supplierId, supplierName, debitNoteNumber, value, reason, createdBy);
    }

    void insertWriteOff(UUID tenantId, UUID id, String method, String note, BigDecimal value, UUID witnessStaffId, UUID approvedBy) {
        jdbc.update("INSERT INTO stock_write_offs (id, tenant_id, method, note, value, witness_staff_id, approved_by) VALUES (?,?,?,?,?,?,?)",
                id, tenantId, method, note, value, witnessStaffId, approvedBy);
    }

    /** @param table supplier_return_lines or stock_write_off_lines (a constant of the caller, never request data) */
    void insertLines(UUID tenantId, String table, UUID documentId, List<DocumentLine> lines) {
        boolean writeOff = "stock_write_off_lines".equals(table);
        String sql = writeOff
                ? "INSERT INTO stock_write_off_lines (tenant_id, write_off_id, line_order, batch_id, item_id, qty, rate, value, register_type) VALUES (?,?,?,?,?,?,?,?,?)"
                : "INSERT INTO supplier_return_lines (tenant_id, return_id, line_order, batch_id, item_id, qty, rate, value) VALUES (?,?,?,?,?,?,?,?)";
        for (int i = 0; i < lines.size(); i++) {
            DocumentLine l = lines.get(i);
            if (writeOff) {
                jdbc.update(sql, tenantId, documentId, i, l.batchId(), l.itemId(), l.qty(), l.rate(), l.value(), l.registerType());
            } else {
                jdbc.update(sql, tenantId, documentId, i, l.batchId(), l.itemId(), l.qty(), l.rate(), l.value());
            }
        }
    }

    /** @param staffName resolves created_by to a name (iam owns staff: the caller asks its ActorDirectory) */
    Optional<SupplierReturn> findReturn(UUID tenantId, UUID id, java.util.function.Function<UUID, String> staffName) {
        List<DocumentLine> lines = jdbc.query("""
                SELECT l.batch_id, l.item_id, c.name, b.batch_no, b.expiry_date, l.qty, l.rate, l.value
                  FROM supplier_return_lines l JOIN stock_batches b ON b.id = l.batch_id AND b.tenant_id = l.tenant_id
                  JOIN charge_catalogue c ON c.id = l.item_id AND c.tenant_id = l.tenant_id
                 WHERE l.tenant_id = ? AND l.return_id = ? ORDER BY l.line_order""",
                (rs, i) -> new DocumentLine(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getObject(5, LocalDate.class), rs.getInt(6), rs.getBigDecimal(7), rs.getBigDecimal(8), null), tenantId, id);
        return jdbc.query("""
                SELECT id, debit_note_number, supplier_id, supplier_name_snapshot, status, value, reason, created_by, created_at
                  FROM supplier_returns WHERE tenant_id = ? AND id = ?""",
                (rs, i) -> new SupplierReturn(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4),
                        rs.getString(5), rs.getBigDecimal(6), rs.getString(7), staffName.apply(rs.getObject(8, UUID.class)),
                        rs.getTimestamp(9).toInstant(), lines),
                tenantId, id).stream().findFirst();
    }
}
