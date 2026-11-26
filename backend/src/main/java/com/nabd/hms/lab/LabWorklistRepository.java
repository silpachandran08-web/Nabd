package com.nabd.hms.lab;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** PHL-044: lab worklist queries. */
@Repository
class LabWorklistRepository {

    record WorklistOrder(UUID id, UUID patientId, String priority, String status, String source, Instant orderedAt, Instant dueAt, int testCount) {
    }

    record StatusCounts(int ordered, int collected, int inProgress, int resulted, int validated) {
    }

    private static final String ORDER_COLS = "id, patient_id, priority, status, source, ordered_at, due_at, "
            + "(SELECT count(*) FROM lab_order_items WHERE order_id = lab_orders.id) AS test_count";

    private static final RowMapper<WorklistOrder> ORDER = (rs, i) -> new WorklistOrder(
            rs.getObject("id", UUID.class),
            rs.getObject("patient_id", UUID.class),
            rs.getString("priority"),
            rs.getString("status"),
            rs.getString("source"),
            rs.getTimestamp("ordered_at").toInstant(),
            rs.getTimestamp("due_at").toInstant(),
            rs.getInt("test_count")
    );

    private final JdbcTemplate jdbc;

    LabWorklistRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Get status counts for the tenant. */
    StatusCounts getCounts(UUID tenantId) {
        return jdbc.queryForObject(
                "SELECT " +
                        "(SELECT count(*) FROM lab_orders WHERE tenant_id = ? AND status = 'ordered') AS ordered, " +
                        "(SELECT count(*) FROM lab_orders WHERE tenant_id = ? AND status = 'collected') AS collected, " +
                        "(SELECT count(*) FROM lab_orders WHERE tenant_id = ? AND status = 'in_progress') AS in_progress, " +
                        "(SELECT count(*) FROM lab_orders WHERE tenant_id = ? AND status = 'resulted') AS resulted, " +
                        "(SELECT count(*) FROM lab_orders WHERE tenant_id = ? AND status = 'validated') AS validated",
                (rs, i) -> new StatusCounts(
                        rs.getInt("ordered"),
                        rs.getInt("collected"),
                        rs.getInt("in_progress"),
                        rs.getInt("resulted"),
                        rs.getInt("validated")
                ),
                tenantId, tenantId, tenantId, tenantId, tenantId
        );
    }

    /** Get orders filtered and sorted: STAT first, then by due_at, then by ordered_at. */
    List<WorklistOrder> getOrders(UUID tenantId, String status, String priority, String source, int limit) {
        List<String> conditions = new ArrayList<>();
        conditions.add("tenant_id = ?");
        conditions.add("status NOT IN ('released', 'cancelled')");

        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (status != null && !status.isEmpty()) {
            conditions.add("status = ?");
            params.add(status);
        }

        if (priority != null && !priority.isEmpty()) {
            conditions.add("priority = ?");
            params.add(priority);
        }

        if (source != null && !source.isEmpty()) {
            conditions.add("source = ?");
            params.add(source);
        }

        String where = String.join(" AND ", conditions);
        String sql = "SELECT " + ORDER_COLS + " FROM lab_orders WHERE " + where
                + " ORDER BY CASE priority WHEN 'stat' THEN 0 WHEN 'urgent' THEN 1 ELSE 2 END, due_at, ordered_at "
                + "LIMIT ?";

        params.add(limit);
        return jdbc.query(sql, ORDER, params.toArray());
    }

    /** Mark order as requiring due_at recomputation (e.g. on TAT index changes); next write recomputes it. */
    void updateDueAt(UUID tenantId, UUID orderId) {
        jdbc.update("UPDATE lab_orders SET due_at = now() WHERE tenant_id = ? AND id = ?", tenantId, orderId);
    }
}
