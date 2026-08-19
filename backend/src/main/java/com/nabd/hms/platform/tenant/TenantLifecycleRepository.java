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

    void updateTenantStatus(UUID tenantId, String status) {
        jdbc.update("UPDATE tenants SET status = ? WHERE id = ?", status, tenantId);
    }

    void insertEvent(UUID tenantId, String fromStatus, String toStatus, UUID changedBy, String reason) {
        jdbc.update("INSERT INTO tenant_lifecycle_events (tenant_id, from_status, to_status, changed_by, reason) " +
                        "VALUES (?,?,?,?,?)",
                tenantId, fromStatus, toStatus, changedBy, reason);
    }

    List<LifecycleEvent> listEvents(UUID tenantId) {
        return jdbc.query(
                "SELECT from_status, to_status, changed_by, reason, changed_at " +
                        "FROM tenant_lifecycle_events WHERE tenant_id = ? ORDER BY changed_at DESC",
                eventMapper(), tenantId);
    }

    private RowMapper<LifecycleEvent> eventMapper() {
        return (rs, i) -> new LifecycleEvent(
                rs.getString("from_status"),
                rs.getString("to_status"),
                UUID.fromString(rs.getString("changed_by")),
                rs.getString("reason"),
                rs.getTimestamp("changed_at").toInstant());
    }
}
