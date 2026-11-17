package com.nabd.hms.pharmacy;

import com.nabd.hms.pharmacy.dto.StockAlertDtos.Alert;
import com.nabd.hms.pharmacy.dto.StockAlertDtos.Counts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PHL-008: stock_alerts and the alert/digest settings on pharmacy_settings. Every statement names the tenant (RLS is the backstop). */
@Repository
class StockAlertRepository {

    /** Defaults apply when the clinic has no pharmacy_settings row yet. */
    record Settings(String expiryDays, int deadStockDays, LocalTime digestTime, LocalDate lastDigestDay, UUID recipientStaffId) {
    }

    record ExpiryRow(UUID batchId, UUID itemId, String itemName, String batchNo, LocalDate expiryDate, int qtyOnHand,
                     BigDecimal purchaseRate, BigDecimal value) {
    }

    static final String EXPIRY_TYPES = "('expiry_90', 'expiry_60', 'expiry_30', 'expired')";
    /** A batch shows only its newest open expiry alert (a tighter threshold supersedes a looser one still open). */
    private static final String NOT_SUPERSEDED = " AND NOT EXISTS (SELECT 1 FROM stock_alerts x WHERE x.tenant_id = a.tenant_id"
            + " AND x.batch_id = a.batch_id AND x.status = 'open' AND x.id > a.id)";

    private final JdbcTemplate jdbc;

    StockAlertRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Settings settings(UUID tenantId) {
        return jdbc.query("SELECT expiry_alert_days::text AS days, dead_stock_days, digest_local_time, last_digest_day, alert_recipient_staff_id " +
                        "FROM pharmacy_settings WHERE tenant_id = ?",
                (rs, i) -> new Settings(rs.getString("days"), rs.getInt("dead_stock_days"), rs.getObject("digest_local_time", LocalTime.class),
                        rs.getObject("last_digest_day", LocalDate.class), rs.getObject("alert_recipient_staff_id", UUID.class)), tenantId)
                .stream().findFirst().orElse(new Settings("{90,60,30}", 90, LocalTime.of(7, 0), null, null));
    }

    /** One alert per batch per threshold, ever: the tightest threshold the batch is inside today, or 'expired' once past it. */
    int raiseExpiry(UUID tenantId, LocalDate today, String expiryDays) {
        return jdbc.update("""
                INSERT INTO stock_alerts (tenant_id, type, item_id, batch_id, value_at_risk, created_on)
                SELECT b.tenant_id, x.type, b.item_id, b.id, round(b.qty_on_hand * COALESCE(b.purchase_rate, 0), 2), ?
                  FROM stock_batches b
                  CROSS JOIN LATERAL (SELECT CASE WHEN b.expiry_date < ? THEN 'expired'
                         ELSE 'expiry_' || (SELECT min(d) FROM unnest(?::int[]) d WHERE b.expiry_date - ?::date <= d) END AS type) x
                 WHERE b.tenant_id = ? AND b.status = 'active' AND b.qty_on_hand > 0 AND b.expiry_date IS NOT NULL AND x.type IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM stock_alerts a WHERE a.tenant_id = b.tenant_id AND a.batch_id = b.id AND a.type = x.type)
                ON CONFLICT DO NOTHING""",
                Date.valueOf(today), Date.valueOf(today), expiryDays, Date.valueOf(today), tenantId);
    }

    /** On hand below the item's reorder level; raised again only after stock came in (a positive movement) since the last alert. */
    int raiseLowStock(UUID tenantId, LocalDate today) {
        return jdbc.update("""
                INSERT INTO stock_alerts (tenant_id, type, item_id, created_on)
                SELECT c.tenant_id, 'low_stock', c.id, ?
                  FROM charge_catalogue c JOIN pharmacy_item_profiles p ON p.item_id = c.id AND p.tenant_id = c.tenant_id
                 WHERE c.tenant_id = ? AND c.active AND p.in_house AND c.stock_qty IS NOT NULL AND p.reorder_level IS NOT NULL
                   AND c.stock_qty < p.reorder_level
                   AND NOT EXISTS (SELECT 1 FROM stock_alerts a WHERE a.tenant_id = c.tenant_id AND a.item_id = c.id AND a.type = 'low_stock'
                         AND a.created_at > COALESCE((SELECT max(m.occurred_at) FROM pharmacy_stock_movements m
                               WHERE m.tenant_id = c.tenant_id AND m.item_id = c.id AND m.delta > 0), '-infinity'::timestamptz))
                ON CONFLICT DO NOTHING""",
                Date.valueOf(today), tenantId);
    }

    /** On hand > 0, held since before the cutoff, no sale/dispense/transfer out since the cutoff; raised again only after it moved. */
    int raiseDeadStock(UUID tenantId, LocalDate today, Instant cutoff) {
        return jdbc.update("""
                INSERT INTO stock_alerts (tenant_id, type, item_id, value_at_risk, created_on)
                SELECT c.tenant_id, 'dead_stock', c.id, v.val, ?
                  FROM charge_catalogue c
                  CROSS JOIN LATERAL (SELECT round(COALESCE(sum(b.qty_on_hand * b.purchase_rate), 0), 2) AS val, min(b.created_at) AS oldest
                         FROM stock_batches b WHERE b.tenant_id = c.tenant_id AND b.item_id = c.id AND b.qty_on_hand > 0 AND b.status = 'active') v
                  CROSS JOIN LATERAL (SELECT max(m.occurred_at) AS last_out FROM pharmacy_stock_movements m
                         WHERE m.tenant_id = c.tenant_id AND m.item_id = c.id AND m.delta < 0 AND m.kind IN ('sale', 'dispense', 'transfer')) o
                 WHERE c.tenant_id = ? AND c.active AND c.stock_qty > 0 AND v.oldest <= ? AND (o.last_out IS NULL OR o.last_out <= ?)
                   AND NOT EXISTS (SELECT 1 FROM stock_alerts a WHERE a.tenant_id = c.tenant_id AND a.item_id = c.id AND a.type = 'dead_stock'
                         AND a.created_at > COALESCE(o.last_out, '-infinity'::timestamptz))
                ON CONFLICT DO NOTHING""",
                Date.valueOf(today), tenantId, Timestamp.from(cutoff), Timestamp.from(cutoff));
    }

    /** Open alerts per digest bucket: expiry_90, expiry_60, expiry_30, expired, low_stock, dead_stock. */
    int[] openCounts(UUID tenantId) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE type = 'expiry_90'), count(*) FILTER (WHERE type = 'expiry_60'),
                       count(*) FILTER (WHERE type = 'expiry_30'), count(*) FILTER (WHERE type = 'expired'),
                       count(*) FILTER (WHERE type = 'low_stock'), count(*) FILTER (WHERE type = 'dead_stock')
                  FROM stock_alerts a WHERE a.tenant_id = ? AND a.status = 'open'""" + NOT_SUPERSEDED,
                (rs, i) -> new int[] {rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6)}, tenantId);
    }

    Counts tabCounts(UUID tenantId) {
        int[] c = openCounts(tenantId);
        return new Counts(c[0] + c[1] + c[2] + c[3], c[4], c[5]);
    }

    /** True once per clinic day: the row lock makes two racing runs send one digest. */
    boolean claimDigestDay(UUID tenantId, LocalDate day) {
        return jdbc.update("UPDATE pharmacy_settings SET last_digest_day = ? WHERE tenant_id = ? AND last_digest_day IS DISTINCT FROM ?",
                Date.valueOf(day), tenantId, Date.valueOf(day)) == 1;
    }

    private static final String ALERT_SELECT = """
            SELECT a.id, a.type, a.item_id, c.name, a.batch_id, b.batch_no, b.expiry_date, COALESCE(c.stock_qty, 0) AS on_hand,
                   p.reorder_level, a.value_at_risk, a.status, a.action, a.created_on
              FROM stock_alerts a JOIN charge_catalogue c ON c.id = a.item_id AND c.tenant_id = a.tenant_id
              LEFT JOIN stock_batches b ON b.id = a.batch_id AND b.tenant_id = a.tenant_id
              LEFT JOIN pharmacy_item_profiles p ON p.item_id = a.item_id AND p.tenant_id = a.tenant_id
            """;

    private RowMapper<Alert> alertMapper(LocalDate today) {
        return (rs, i) -> {
            LocalDate expiry = rs.getObject("expiry_date", LocalDate.class);
            return new Alert(rs.getObject("id", UUID.class), rs.getString("type"), rs.getObject("item_id", UUID.class), rs.getString("name"),
                    rs.getObject("batch_id", UUID.class), rs.getString("batch_no"), expiry,
                    expiry == null ? null : (int) java.time.temporal.ChronoUnit.DAYS.between(today, expiry), rs.getInt("on_hand"),
                    (Integer) rs.getObject("reorder_level"), rs.getBigDecimal("value_at_risk"), rs.getString("status"), rs.getString("action"),
                    rs.getObject("created_on", LocalDate.class));
        };
    }

    /** @param types SQL list of types, null = every open alert; page in id (raise) order after {@code afterId} */
    List<Alert> openPage(UUID tenantId, String types, UUID afterId, int limit, LocalDate today) {
        return jdbc.query(ALERT_SELECT + " WHERE a.tenant_id = ? AND a.status = 'open'" + NOT_SUPERSEDED + (types == null ? "" : " AND a.type IN " + types) +
                        " AND (?::uuid IS NULL OR a.id > ?::uuid) ORDER BY a.id LIMIT ?",
                alertMapper(today), tenantId, afterId, afterId, limit);
    }

    Optional<Alert> find(UUID tenantId, UUID id, LocalDate today) {
        return jdbc.query(ALERT_SELECT + " WHERE a.tenant_id = ? AND a.id = ?", alertMapper(today), tenantId, id).stream().findFirst();
    }

    /** Acknowledges the alert and the older open expiry alerts of the same batch it superseded.
     * @return false when the alert was already acknowledged (or is not this clinic's) */
    boolean acknowledge(UUID tenantId, UUID id, String action, String note, UUID staffId) {
        String set = "UPDATE stock_alerts SET status = 'acknowledged', action = ?, note = ?, acknowledged_by = ?, acknowledged_at = now() ";
        if (jdbc.update(set + "WHERE tenant_id = ? AND id = ? AND status = 'open'", action, note, staffId, tenantId, id) != 1) {
            return false;
        }
        jdbc.update(set + "WHERE tenant_id = ? AND status = 'open' AND id < ? AND batch_id = (SELECT batch_id FROM stock_alerts WHERE tenant_id = ? AND id = ?)",
                action, note, staffId, tenantId, id, tenantId, id);
        return true;
    }

    /** Active batches on hand expiring on or before {@code by} (already expired included), soonest first. */
    List<ExpiryRow> expiring(UUID tenantId, LocalDate by) {
        return jdbc.query("""
                SELECT b.id, b.item_id, c.name, b.batch_no, b.expiry_date, b.qty_on_hand, b.purchase_rate,
                       round(b.qty_on_hand * COALESCE(b.purchase_rate, 0), 2) AS val
                  FROM stock_batches b JOIN charge_catalogue c ON c.id = b.item_id AND c.tenant_id = b.tenant_id
                 WHERE b.tenant_id = ? AND b.status = 'active' AND b.qty_on_hand > 0 AND b.expiry_date IS NOT NULL AND b.expiry_date <= ?
                 ORDER BY b.expiry_date, lower(c.name), b.id""",
                (rs, i) -> new ExpiryRow(rs.getObject("id", UUID.class), rs.getObject("item_id", UUID.class), rs.getString("name"),
                        rs.getString("batch_no"), rs.getObject("expiry_date", LocalDate.class), rs.getInt("qty_on_hand"),
                        rs.getBigDecimal("purchase_rate"), rs.getBigDecimal("val")), tenantId, Date.valueOf(by));
    }
}
