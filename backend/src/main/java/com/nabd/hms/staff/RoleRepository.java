package com.nabd.hms.staff;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.staff.StaffRoleModels.RoleRow;

@Repository
class RoleRepository {

    private final JdbcTemplate jdbc;

    RoleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<RoleRow> list(UUID tenantId) {
        return jdbc.query(
                "SELECT id, tenant_id, name, built_in, grants::text AS grants_json FROM roles WHERE tenant_id = ? ORDER BY name",
                roleMapper(), tenantId);
    }

    Optional<RoleRow> findById(UUID tenantId, UUID id) {
        return jdbc.query(
                "SELECT id, tenant_id, name, built_in, grants::text AS grants_json FROM roles WHERE tenant_id = ? AND id = ?",
                roleMapper(), tenantId, id
        ).stream().findFirst();
    }

    void insert(UUID id, UUID tenantId, String name, String grantsJson) {
        jdbc.update("INSERT INTO roles (id, tenant_id, name, built_in, grants) VALUES (?,?,?,false,?::jsonb)",
                id, tenantId, name, grantsJson);
    }

    void update(UUID tenantId, UUID id, String name, String grantsJson) {
        jdbc.update("UPDATE roles SET name = ?, grants = ?::jsonb WHERE tenant_id = ? AND id = ? AND built_in = false",
                name, grantsJson, tenantId, id);
    }

    private RowMapper<RoleRow> roleMapper() {
        return (rs, i) -> new RoleRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                rs.getString("name"),
                rs.getBoolean("built_in"),
                rs.getString("grants_json"));
    }
}
