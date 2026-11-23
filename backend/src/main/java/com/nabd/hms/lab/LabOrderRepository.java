package com.nabd.hms.lab;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PHL-043: lab_orders and lab_order_items (V3630). Test and panel names come from LabRepository; prices from the price master. */
@Repository
class LabOrderRepository {

    record OrderRow(UUID id, UUID patientId, UUID queueEntryId, UUID orderingDoctorId, String priority, String destinationKind,
                    String status, String clinicalNote, Integer fastingHours, Instant orderedAt) {
    }

    /** panelId null = ordered on its own. chargeLineRef: the billing pending charge, null when nothing was charged. */
    record ItemRow(UUID id, UUID orderId, UUID testId, UUID panelId, String status, BigDecimal price, UUID chargeLineRef) {
    }

    record FavouriteRow(UUID testId, UUID panelId, int orders) {
    }

    private static final String ORDER_COLS = "id, patient_id, queue_entry_id, ordering_doctor_id, priority, destination_kind, status, "
            + "clinical_note, fasting_hours, ordered_at";
    private static final RowMapper<OrderRow> ORDER = (rs, i) -> new OrderRow(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
            rs.getObject("queue_entry_id", UUID.class), rs.getObject("ordering_doctor_id", UUID.class), rs.getString("priority"),
            rs.getString("destination_kind"), rs.getString("status"), rs.getString("clinical_note"), (Integer) rs.getObject("fasting_hours"),
            rs.getTimestamp("ordered_at").toInstant());

    private final JdbcTemplate jdbc;

    LabOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    UUID insertOrder(UUID tenantId, UUID patientId, UUID encounterId, UUID queueEntryId, UUID doctorId, String source, String priority,
                     String destinationKind, String clinicalNote, Integer fastingHours) {
        return jdbc.queryForObject("INSERT INTO lab_orders (tenant_id, patient_id, encounter_id, queue_entry_id, ordering_doctor_id, source, priority, "
                        + "destination_kind, clinical_note, fasting_hours) VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id", UUID.class,
                tenantId, patientId, encounterId, queueEntryId, doctorId, source, priority, destinationKind, clinicalNote, fastingHours);
    }

    /** Rows in display order (the order the doctor picked them; a panel's tests in the panel's order). */
    void insertItems(UUID tenantId, UUID orderId, List<ItemRow> items) {
        for (int i = 0; i < items.size(); i++) {
            ItemRow it = items.get(i);
            jdbc.update("INSERT INTO lab_order_items (tenant_id, order_id, test_id, panel_id, price_snapshot, charge_line_ref, display_order) "
                    + "VALUES (?,?,?,?,?,?,?)", tenantId, orderId, it.testId(), it.panelId(), it.price(), it.chargeLineRef(), i + 1);
        }
    }

    Optional<OrderRow> find(UUID tenantId, UUID id, boolean forUpdate) {
        return jdbc.query("SELECT " + ORDER_COLS + " FROM lab_orders WHERE tenant_id = ? AND id = ?" + (forUpdate ? " FOR UPDATE" : ""), ORDER,
                tenantId, id).stream().findFirst();
    }

    /** STAT first whatever the time, then urgent, then routine; oldest first inside a priority. */
    List<OrderRow> byVisit(UUID tenantId, UUID queueEntryId) {
        return jdbc.query("SELECT " + ORDER_COLS + " FROM lab_orders WHERE tenant_id = ? AND queue_entry_id = ? "
                + "ORDER BY CASE priority WHEN 'stat' THEN 0 WHEN 'urgent' THEN 1 ELSE 2 END, ordered_at, id", ORDER, tenantId, queueEntryId);
    }

    List<ItemRow> items(UUID tenantId, Collection<UUID> orderIds) {
        return jdbc.query("SELECT id, order_id, test_id, panel_id, status, price_snapshot, charge_line_ref FROM lab_order_items "
                        + "WHERE tenant_id = ? AND order_id = ANY (?) ORDER BY order_id, display_order",
                (rs, i) -> new ItemRow(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getObject("test_id", UUID.class),
                        rs.getObject("panel_id", UUID.class), rs.getString("status"), rs.getBigDecimal("price_snapshot"),
                        rs.getObject("charge_line_ref", UUID.class)), tenantId, orderIds.toArray(new UUID[0]));
    }

    /** Only an order nothing was collected for (status ordered) is cancelled; returns whether this call did it. */
    boolean cancel(UUID tenantId, UUID id, UUID staffId, String reason) {
        boolean done = jdbc.update("UPDATE lab_orders SET status = 'cancelled', cancelled_at = now(), cancelled_by = ?, cancel_reason = ? "
                + "WHERE tenant_id = ? AND id = ? AND status = 'ordered'", staffId, reason, tenantId, id) == 1;
        if (done) jdbc.update("UPDATE lab_order_items SET status = 'cancelled' WHERE tenant_id = ? AND order_id = ?", tenantId, id);
        return done;
    }

    /** What this doctor ordered most (a panel counts as the panel, not its tests), most ordered first, then most recent. */
    List<FavouriteRow> favourites(UUID tenantId, UUID doctorId, int limit) {
        return jdbc.query("SELECT CASE WHEN i.panel_id IS NULL THEN i.test_id END AS test_id, i.panel_id, count(DISTINCT o.id) AS orders, "
                        + "max(o.ordered_at) AS last_ordered FROM lab_order_items i JOIN lab_orders o ON o.id = i.order_id AND o.tenant_id = i.tenant_id "
                        + "WHERE i.tenant_id = ? AND o.ordering_doctor_id = ? AND o.status <> 'cancelled' "
                        + "GROUP BY 1, 2 ORDER BY orders DESC, last_ordered DESC LIMIT ?",
                (rs, i) -> new FavouriteRow(rs.getObject("test_id", UUID.class), rs.getObject("panel_id", UUID.class), rs.getInt("orders")),
                tenantId, doctorId, limit);
    }

    /** The clinic's name for the order sheet's letterhead. */
    String clinicName(UUID tenantId) {
        return jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, tenantId);
    }
}
