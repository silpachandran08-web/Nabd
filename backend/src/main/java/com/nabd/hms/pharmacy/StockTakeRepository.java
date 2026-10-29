package com.nabd.hms.pharmacy;

import com.nabd.hms.pharmacy.dto.StockTakeDtos.AdjustmentView;
import com.nabd.hms.pharmacy.dto.StockTakeDtos.Line;
import com.nabd.hms.pharmacy.dto.StockTakeDtos.Summary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PHL-010: stock_takes and stock_take_counts. Stock itself only changes through StockCommands. */
@Repository
class StockTakeRepository {

    record Session(UUID id, String scope, String scopeValue, String status) {
    }

    /** A sheet line plus the batch's purchase rate (for the variance value). */
    record LineRow(Line line, BigDecimal purchaseRate) {
    }

    private static final String SUMMARY = "SELECT t.id, t.scope, t.scope_value, t.status, t.snapshot_at, cb.name AS created_name, pb.name AS posted_name, " +
            "ab.name AS approved_name, t.posted_at, (SELECT count(*) FROM stock_take_counts c WHERE c.tenant_id = t.tenant_id AND c.stock_take_id = t.id) AS n, " +
            "(SELECT count(*) FROM stock_take_counts c WHERE c.tenant_id = t.tenant_id AND c.stock_take_id = t.id AND c.counted_qty IS NOT NULL) AS counted " +
            "FROM stock_takes t LEFT JOIN staff cb ON cb.id = t.created_by LEFT JOIN staff pb ON pb.id = t.posted_by LEFT JOIN staff ab ON ab.id = t.approved_by ";

    private static final RowMapper<Summary> SUMMARY_MAP = (rs, i) -> new Summary(rs.getObject("id", UUID.class), rs.getString("scope"),
            rs.getString("scope_value"), rs.getString("status"), rs.getTimestamp("snapshot_at").toInstant(), rs.getString("created_name"),
            rs.getString("posted_name"), rs.getString("approved_name"),
            rs.getTimestamp("posted_at") == null ? null : rs.getTimestamp("posted_at").toInstant(), rs.getInt("n"), rs.getInt("counted"));

    private final JdbcTemplate jdbc;

    StockTakeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Session> lock(UUID tenantId, UUID id) {
        return jdbc.query("SELECT id, scope, scope_value, status FROM stock_takes WHERE tenant_id = ? AND id = ? FOR UPDATE",
                (rs, i) -> new Session(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)), tenantId, id).stream().findFirst();
    }

    Optional<Session> findOpen(UUID tenantId) {
        return jdbc.query("SELECT id, scope, scope_value, status FROM stock_takes WHERE tenant_id = ? AND status = 'open'",
                (rs, i) -> new Session(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)), tenantId).stream().findFirst();
    }

    UUID insert(UUID tenantId, String scope, String scopeValue, UUID staffId) {
        return jdbc.queryForObject("INSERT INTO stock_takes (tenant_id, scope, scope_value, created_by) VALUES (?,?,?,?) RETURNING id",
                UUID.class, tenantId, scope, scopeValue, staffId);
    }

    /** The frozen sheet: every batch with stock in scope, its quantity and its last movement seq, read in one statement (one snapshot). */
    int snapshot(UUID tenantId, UUID id, String scope, String scopeValue) {
        return jdbc.update("INSERT INTO stock_take_counts (stock_take_id, tenant_id, batch_id, item_id, system_qty_at_snapshot, snapshot_seq) " +
                        "SELECT ?, b.tenant_id, b.id, b.item_id, b.qty_on_hand, COALESCE((SELECT max(m.seq) FROM pharmacy_stock_movements m " +
                        "  WHERE m.tenant_id = b.tenant_id AND m.batch_id = b.id), 0) " +
                        "FROM stock_batches b JOIN charge_catalogue c ON c.id = b.item_id AND c.tenant_id = b.tenant_id AND c.stock_qty IS NOT NULL " +
                        "LEFT JOIN pharmacy_item_profiles p ON p.item_id = b.item_id AND p.tenant_id = b.tenant_id " +
                        "WHERE b.tenant_id = ? AND b.qty_on_hand > 0 AND (? = 'all' OR (? = 'location' AND b.location = ?) " +
                        "  OR (? = 'class' AND COALESCE(p.schedule_class, 'OTC') = ?))",
                id, tenantId, scope, scope, scopeValue, scope, scopeValue);
    }

    /** Set-based count upsert; the count's own movement seq is read in the same statement. Returns the lines changed. */
    int saveCounts(UUID tenantId, UUID id, UUID staffId, UUID[] batchIds, Integer[] counted, String[] codes, String[] notes) {
        return jdbc.update(con -> {
            var ps = con.prepareStatement("UPDATE stock_take_counts c SET counted_qty = d.counted, counted_by = ?, counted_at = now(), " +
                    "reason_code = d.code, note = d.note, counted_seq = COALESCE((SELECT max(m.seq) FROM pharmacy_stock_movements m " +
                    "  WHERE m.tenant_id = c.tenant_id AND m.batch_id = c.batch_id), 0) " +
                    "FROM unnest(?::uuid[], ?::int[], ?::text[], ?::text[]) AS d(batch_id, counted, code, note) " +
                    "WHERE c.tenant_id = ? AND c.stock_take_id = ? AND c.batch_id = d.batch_id");
            ps.setObject(1, staffId);
            ps.setArray(2, con.createArrayOf("uuid", batchIds));
            ps.setArray(3, con.createArrayOf("int4", counted));
            ps.setArray(4, con.createArrayOf("text", codes));
            ps.setArray(5, con.createArrayOf("text", notes));
            ps.setObject(6, tenantId);
            ps.setObject(7, id);
            return ps;
        });
    }

    /** Count sheet by location, then item name, then expiry. Movements since the snapshot stop at the count (a sale after it is no variance). */
    List<LineRow> lines(UUID tenantId, UUID id) {
        return jdbc.query("SELECT c.batch_id, c.item_id, ci.code, ci.name, COALESCE(p.schedule_class, 'OTC') AS cls, " +
                        "EXISTS (SELECT 1 FROM pharmacy_schedule_rules r WHERE r.schedule_class = p.schedule_class AND r.register_type IS NOT NULL) AS controlled, " +
                        "b.batch_no, b.expiry_date, b.location, b.purchase_rate, c.system_qty_at_snapshot, c.counted_qty, c.reason_code, c.note, c.posted_delta, " +
                        "COALESCE((SELECT sum(m.delta) FROM pharmacy_stock_movements m WHERE m.tenant_id = c.tenant_id AND m.batch_id = c.batch_id " +
                        "  AND m.seq > c.snapshot_seq AND (c.counted_seq IS NULL OR m.seq <= c.counted_seq)), 0) AS moved " +
                        "FROM stock_take_counts c JOIN stock_batches b ON b.id = c.batch_id AND b.tenant_id = c.tenant_id " +
                        "JOIN charge_catalogue ci ON ci.id = c.item_id AND ci.tenant_id = c.tenant_id " +
                        "LEFT JOIN pharmacy_item_profiles p ON p.item_id = c.item_id AND p.tenant_id = c.tenant_id " +
                        "WHERE c.tenant_id = ? AND c.stock_take_id = ? ORDER BY b.location NULLS LAST, lower(ci.name), b.expiry_date NULLS LAST, b.id",
                (rs, i) -> {
                    int snap = rs.getInt("system_qty_at_snapshot");
                    int moved = rs.getInt("moved");
                    Integer counted = (Integer) rs.getObject("counted_qty");
                    return new LineRow(new Line(rs.getObject("batch_id", UUID.class), rs.getObject("item_id", UUID.class), rs.getString("code"),
                            rs.getString("name"), rs.getString("cls"), rs.getBoolean("controlled"), rs.getString("batch_no"),
                            rs.getObject("expiry_date", LocalDate.class), rs.getString("location"), snap, moved, snap + moved, counted,
                            counted == null ? null : counted - (snap + moved), rs.getString("reason_code"), rs.getString("note"),
                            (Integer) rs.getObject("posted_delta")), rs.getBigDecimal("purchase_rate"));
                }, tenantId, id);
    }

    void markPosted(UUID tenantId, UUID id, UUID[] batchIds, Integer[] deltas) {
        jdbc.update(con -> {
            var ps = con.prepareStatement("UPDATE stock_take_counts c SET posted_delta = d.delta FROM unnest(?::uuid[], ?::int[]) AS d(batch_id, delta) " +
                    "WHERE c.tenant_id = ? AND c.stock_take_id = ? AND c.batch_id = d.batch_id");
            ps.setArray(1, con.createArrayOf("uuid", batchIds));
            ps.setArray(2, con.createArrayOf("int4", deltas));
            ps.setObject(3, tenantId);
            ps.setObject(4, id);
            return ps;
        });
    }

    void close(UUID tenantId, UUID id, String status, UUID postedBy, UUID approvedBy) {
        jdbc.update("UPDATE stock_takes SET status = ?, posted_by = ?, approved_by = ?, posted_at = CASE WHEN ? = 'posted' THEN now() END " +
                "WHERE tenant_id = ? AND id = ?", status, postedBy, approvedBy, status, tenantId, id);
    }

    Optional<Summary> summary(UUID tenantId, UUID id) {
        return jdbc.query(SUMMARY + "WHERE t.tenant_id = ? AND t.id = ?", SUMMARY_MAP, tenantId, id).stream().findFirst();
    }

    List<Summary> recent(UUID tenantId, int limit) {
        return jdbc.query(SUMMARY + "WHERE t.tenant_id = ? ORDER BY t.snapshot_at DESC, t.id DESC LIMIT ?", SUMMARY_MAP, tenantId, limit);
    }

    /** The adjustments a posted session wrote (replay of an idempotent post). */
    List<AdjustmentView> postedAdjustments(UUID tenantId, UUID id) {
        return jdbc.query("SELECT m.batch_id, m.item_id, m.delta, m.qty_before, m.qty_after, m.reason_code FROM stock_take_counts c " +
                        "JOIN pharmacy_stock_movements m ON m.tenant_id = c.tenant_id AND m.batch_id = c.batch_id AND m.source_type = 'stock_take' " +
                        "AND m.source_id = c.stock_take_id WHERE c.tenant_id = ? AND c.stock_take_id = ? AND c.posted_delta IS NOT NULL ORDER BY m.seq",
                (rs, i) -> new AdjustmentView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                        rs.getString(6)), tenantId, id);
    }

    /** Whether the batch's item is in a class that needs a statutory register (controlled: a second sign-off for any adjustment);
     * empty for an unknown batch. */
    Optional<Boolean> controlledBatch(UUID tenantId, UUID batchId) {
        return jdbc.query("SELECT EXISTS (SELECT 1 FROM pharmacy_item_profiles p JOIN pharmacy_schedule_rules r ON r.schedule_class = p.schedule_class " +
                        "WHERE p.tenant_id = b.tenant_id AND p.item_id = b.item_id AND r.register_type IS NOT NULL) FROM stock_batches b WHERE b.tenant_id = ? AND b.id = ?",
                (rs, i) -> rs.getBoolean(1), tenantId, batchId).stream().findFirst();
    }
}
