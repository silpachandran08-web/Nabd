package com.nabd.hms.platform.tenant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.platform.tenant.TenantLifecycleModels.LifecycleEvent;

@Repository
class TenantLifecycleRepository {

    private final JdbcTemplate jdbc;

    TenantLifecycleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<String> findTenantStatus(UUID tenantId) {
        return jdbc.query("SELECT status FROM tenants WHERE id = ?", (rs, i) -> rs.getString("status"), tenantId)
                .stream().findFirst();
    }

    /** ADM-020: {name, slug}, so the console names the clinic the operator is about to move. */
    Optional<String[]> findTenantLabel(UUID tenantId) {
        return jdbc.query("SELECT name, slug FROM tenants WHERE id = ?", (rs, i) -> new String[] {rs.getString("name"), rs.getString("slug")}, tenantId)
                .stream().findFirst();
    }

    void updateTenantStatus(UUID tenantId, String status) {
        jdbc.update("UPDATE tenants SET status = ? WHERE id = ?", status, tenantId);
    }

    void markLive(UUID tenantId, UUID staffId) {
        jdbc.update("UPDATE tenants SET setup_completed_at = COALESCE(setup_completed_at, now()), go_live_by = COALESCE(go_live_by, ?), updated_at = now() WHERE id = ?", staffId, tenantId);
    }

    long insertEvent(UUID tenantId, String fromStatus, String toStatus, UUID changedBy, UUID changedByStaff, String reason) {
        return jdbc.queryForObject("INSERT INTO tenant_lifecycle_events (tenant_id, from_status, to_status, changed_by, changed_by_staff, reason) " +
                        "VALUES (?,?,?,?,?,?) RETURNING id", Long.class,
                tenantId, fromStatus, toStatus, changedBy, changedByStaff, reason);
    }

    List<LifecycleEvent> listEvents(UUID tenantId) {
        return jdbc.query(
                // ADM-020: who did it. An operator by name; a clinic staff member (go live) and the system (trial clock) by kind only.
                "SELECT e.from_status, e.to_status, e.changed_by, e.reason, e.changed_at, o.name AS operator_name, " +
                        "CASE WHEN e.changed_by IS NOT NULL THEN 'operator' WHEN e.changed_by_staff IS NOT NULL THEN 'staff' ELSE 'system' END AS actor_type " +
                        "FROM tenant_lifecycle_events e LEFT JOIN master.operators o ON o.id = e.changed_by " +
                        "WHERE e.tenant_id = ? ORDER BY e.changed_at DESC, e.id DESC",
                eventMapper(), tenantId);
    }

    private RowMapper<LifecycleEvent> eventMapper() {
        return (rs, i) -> new LifecycleEvent(
                rs.getString("from_status"),
                rs.getString("to_status"),
                rs.getString("changed_by") == null ? null : UUID.fromString(rs.getString("changed_by")), // ADM-004/011: null = owner or system
                rs.getString("reason"),
                rs.getTimestamp("changed_at").toInstant(),
                rs.getString("actor_type"),
                rs.getString("operator_name"));
    }
}
