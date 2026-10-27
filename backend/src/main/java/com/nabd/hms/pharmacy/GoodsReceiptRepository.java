package com.nabd.hms.pharmacy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** PHL-007: goods_receipts and goods_receipt_lines. Stock itself is never written here (StockCommands does that). */
@Repository
class GoodsReceiptRepository {

    private final JdbcTemplate jdbc;

    GoodsReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean billExists(UUID tenantId, String supplierKey, String billNumber) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM goods_receipts WHERE tenant_id = ? AND supplier_key = ? AND bill_number = ?)",
                Boolean.class, tenantId, supplierKey, billNumber));
    }

    UUID insertReceipt(UUID tenantId, UUID supplierId, String supplierName, String supplierKey, String billNumber, LocalDate billDate,
                       BigDecimal subtotal, int lineCount, UUID postedBy) {
        return jdbc.queryForObject("INSERT INTO goods_receipts (tenant_id, supplier_id, supplier_name_snapshot, supplier_key, bill_number, " +
                        "bill_date, subtotal, line_count, posted_by) VALUES (?,?,?,?,?,?,?,?,?) RETURNING id",
                UUID.class, tenantId, supplierId, supplierName, supplierKey, billNumber, java.sql.Date.valueOf(billDate), subtotal, lineCount, postedBy);
    }

    void insertLine(UUID tenantId, UUID receiptId, int order, UUID itemId, String batchNo, LocalDate expiry, BigDecimal mrp, BigDecimal rate,
                    int qty, int freeQty, BigDecimal taxRate, BigDecimal effectiveRate, BigDecimal lineTotal) {
        jdbc.update("INSERT INTO goods_receipt_lines (tenant_id, receipt_id, line_order, item_id, batch_no, expiry_date, mrp, rate, qty, " +
                        "free_qty, tax_rate, effective_rate, line_total) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                tenantId, receiptId, order, itemId, batchNo, java.sql.Date.valueOf(expiry), mrp, rate, qty, freeQty, taxRate, effectiveRate, lineTotal);
    }
}
