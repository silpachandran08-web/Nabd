package com.nabd.hms.pharmacy;

import com.nabd.hms.pharmacy.FefoPicker.Batch;
import com.nabd.hms.shared.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PHL-005: stock_batches and the per-batch movement rows. Only StockCommandsImpl writes (see StockWriteOwnershipTest). */
@Repository
class BatchRepository {

    static final String OPENING = "OPENING";

    record StockRow(UUID itemId, String code, String name, String scheduleClass, int onHand, LocalDate nearestExpiry,
                    int batchCount, boolean expiryUnknown, BigDecimal value) {
    }

    record MovementRow(UUID id, String kind, int delta, int qtyBefore, int qtyAfter, String reason, Instant occurredAt) {
    }

    private static final String COLS = "id, batch_no, expiry_date, mrp, purchase_rate, qty_on_hand, status, location";

    private static final RowMapper<Batch> MAP = (rs, i) -> new Batch(UUID.fromString(rs.getString("id")), rs.getString("batch_no"),
            rs.getObject("expiry_date", LocalDate.class), rs.getBigDecimal("mrp"), rs.getBigDecimal("purchase_rate"),
            rs.getInt("qty_on_hand"), rs.getString("status"), rs.getString("location"));

    private final JdbcTemplate jdbc;

    BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** FEFO order (unknown expiry last, ties by id). */
    List<Batch> list(UUID tenantId, UUID itemId, boolean includeEmpty) {
        return jdbc.query("SELECT " + COLS + " FROM stock_batches WHERE tenant_id = ? AND item_id = ? AND (? OR qty_on_hand > 0) " +
                "ORDER BY expiry_date NULLS LAST, id", MAP, tenantId, itemId, includeEmpty);
    }

    /** The FEFO pick candidates; the literal predicate is what lets the partial index idx_stock_batches_fefo serve it. */
    List<Batch> fefo(UUID tenantId, UUID itemId) {
        return jdbc.query("SELECT " + COLS + " FROM stock_batches WHERE tenant_id = ? AND item_id = ? AND qty_on_hand > 0 AND status = 'active' " +
                "ORDER BY expiry_date NULLS LAST, id", MAP, tenantId, itemId);
    }

    /** Row lock until the end of the transaction; the item row is already locked by the caller, so this only guards the PATCH path. */
    Optional<Batch> lock(UUID tenantId, UUID batchId) {
        return jdbc.query("SELECT " + COLS + " FROM stock_batches WHERE tenant_id = ? AND id = ? FOR UPDATE", MAP, tenantId, batchId)
                .stream().findFirst();
    }

    Optional<UUID> itemOf(UUID tenantId, UUID batchId) {
        return jdbc.query("SELECT item_id FROM stock_batches WHERE tenant_id = ? AND id = ?",
                (rs, i) -> UUID.fromString(rs.getString(1)), tenantId, batchId).stream().findFirst();
    }

    int sumQty(UUID tenantId, UUID itemId) {
        return jdbc.queryForObject("SELECT COALESCE(sum(qty_on_hand), 0) FROM stock_batches WHERE tenant_id = ? AND item_id = ?",
                Integer.class, tenantId, itemId);
    }

    Optional<Batch> find(UUID tenantId, UUID itemId, String batchNo, LocalDate expiry) {
        return jdbc.query("SELECT " + COLS + " FROM stock_batches WHERE tenant_id = ? AND item_id = ? AND batch_no = ? " +
                "AND expiry_date IS NOT DISTINCT FROM ?", MAP, tenantId, itemId, batchNo, expiry == null ? null : Date.valueOf(expiry))
                .stream().findFirst();
    }

    UUID insert(UUID tenantId, UUID itemId, String batchNo, LocalDate expiry, BigDecimal mrp, BigDecimal rate, String location,
                UUID supplierId, UUID grnLineId) {
        UUID id = UuidV7.next();
        jdbc.update("INSERT INTO stock_batches (id, tenant_id, item_id, batch_no, expiry_date, mrp, purchase_rate, qty_on_hand, location, " +
                        "supplier_id, grn_line_id) VALUES (?,?,?,?,?,?,?,0,?,?,?)",
                id, tenantId, itemId, batchNo, expiry == null ? null : Date.valueOf(expiry), mrp, rate, location, supplierId, grnLineId);
        return id;
    }

    /** Guarded, so a batch never goes below zero; empty = would go negative. */
    Optional<Integer> applyDelta(UUID tenantId, UUID batchId, int delta) {
        return jdbc.query("UPDATE stock_batches SET qty_on_hand = qty_on_hand + ? WHERE tenant_id = ? AND id = ? AND qty_on_hand + ? >= 0 " +
                "RETURNING qty_on_hand", (rs, i) -> rs.getInt(1), delta, tenantId, batchId, delta).stream().findFirst();
    }

    void insertMovement(UUID tenantId, UUID itemId, UUID batchId, String kind, int delta, int before, int after, String reason,
                        UUID actorId, UUID invoiceId, String sourceType, UUID sourceId, String overrideReason) {
        jdbc.update("INSERT INTO pharmacy_stock_movements (id, tenant_id, item_id, batch_id, kind, delta, qty_before, qty_after, reason, " +
                        "actor_id, invoice_id, source_type, source_id, fefo_override_reason) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UuidV7.next(), tenantId, itemId, batchId, kind, delta, before, after, reason, actorId, invoiceId, sourceType, sourceId, overrideReason);
    }

    /** Units of this invoice and item still out of each batch it was dispensed from (dispensed minus restocked). */
    List<Object[]> outstandingForInvoice(UUID tenantId, UUID itemId, UUID invoiceId) {
        return jdbc.query("SELECT batch_id, -sum(delta) AS out FROM pharmacy_stock_movements WHERE tenant_id = ? AND item_id = ? " +
                        "AND invoice_id = ? AND batch_id IS NOT NULL AND kind IN ('dispense', 'restock') GROUP BY batch_id HAVING -sum(delta) > 0 " +
                        "ORDER BY batch_id",
                (rs, i) -> new Object[]{UUID.fromString(rs.getString(1)), rs.getInt(2)}, tenantId, itemId, invoiceId);
    }

    void patch(UUID tenantId, UUID batchId, String batchNo, LocalDate expiry, String location) {
        jdbc.update("UPDATE stock_batches SET batch_no = COALESCE(?, batch_no), expiry_date = COALESCE(?, expiry_date), " +
                        "location = COALESCE(?, location) WHERE tenant_id = ? AND id = ?",
                batchNo, expiry == null ? null : Date.valueOf(expiry), location, tenantId, batchId);
    }

    Optional<Instant> lastSaleAt(UUID tenantId, UUID batchId) {
        return jdbc.query("SELECT max(occurred_at) FROM pharmacy_stock_movements WHERE tenant_id = ? AND batch_id = ? " +
                        "AND kind IN ('dispense', 'sale')",
                (rs, i) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), tenantId, batchId)
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }

    List<MovementRow> movements(UUID tenantId, UUID batchId) {
        return jdbc.query("SELECT id, kind, delta, qty_before, qty_after, reason, occurred_at FROM pharmacy_stock_movements " +
                        "WHERE tenant_id = ? AND batch_id = ? ORDER BY seq DESC LIMIT 200",
                (rs, i) -> new MovementRow(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                        rs.getString(6), rs.getTimestamp(7).toInstant()), tenantId, batchId);
    }

    /** Stock screen: one page by (lower(name), id). {@code afterName}/{@code afterId} null = first page. */
    List<StockRow> stockPage(UUID tenantId, String q, String scheduleClass, LocalDate expiringBy, String afterName, UUID afterId, int limit) {
        return jdbc.query("SELECT c.id, c.code, c.name, COALESCE(p.schedule_class, 'OTC') AS cls, c.stock_qty, b.nearest, b.n, b.unknown, b.val " +
                        "FROM charge_catalogue c LEFT JOIN pharmacy_item_profiles p ON p.item_id = c.id AND p.tenant_id = c.tenant_id " +
                        "LEFT JOIN LATERAL (SELECT min(expiry_date) FILTER (WHERE qty_on_hand > 0 AND status = 'active') AS nearest, " +
                        "  count(*) FILTER (WHERE qty_on_hand > 0) AS n, COALESCE(bool_or(expiry_date IS NULL) FILTER (WHERE qty_on_hand > 0), false) AS unknown, " +
                        "  COALESCE(sum(qty_on_hand * purchase_rate), 0) AS val FROM stock_batches s WHERE s.tenant_id = c.tenant_id AND s.item_id = c.id) b ON true " +
                        "WHERE c.tenant_id = ? AND c.stock_qty IS NOT NULL AND (?::text IS NULL OR lower(c.name) LIKE ? ESCAPE '\\') " +
                        "AND (?::text IS NULL OR p.schedule_class = ?) " +
                        "AND (?::date IS NULL OR EXISTS (SELECT 1 FROM stock_batches s WHERE s.tenant_id = c.tenant_id AND s.item_id = c.id " +
                        "  AND s.qty_on_hand > 0 AND s.expiry_date <= ?)) " +
                        "AND (?::text IS NULL OR (lower(c.name), c.id) > (?::text, ?::uuid)) ORDER BY lower(c.name), c.id LIMIT ?",
                (rs, i) -> new StockRow(UUID.fromString(rs.getString("id")), rs.getString("code"), rs.getString("name"), rs.getString("cls"),
                        rs.getInt("stock_qty"), rs.getObject("nearest", LocalDate.class), rs.getInt("n"), rs.getBoolean("unknown"),
                        rs.getBigDecimal("val")),
                tenantId, q == null ? null : "%" + q.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%",
                q == null ? null : "%" + q.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%",
                scheduleClass, scheduleClass, expiringBy == null ? null : Date.valueOf(expiringBy),
                expiringBy == null ? null : Date.valueOf(expiringBy),
                afterName, afterName, afterId == null ? null : afterId.toString(), limit);
    }
}
