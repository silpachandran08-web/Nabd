package com.nabd.hms.staff;

import com.nabd.hms.common.ProvisioningSeeds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** PLT-036: provisioning's writes on roles and staff, moved unchanged from platform's ProvisioningRepository. */
@Repository
class ProvisioningSeedRepository implements ProvisioningSeeds.Iam {

    private final JdbcTemplate jdbc;

    ProvisioningSeedRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertPresetRole(UUID tenantId, String name, boolean builtIn, String grantsJson, String presetCode, int presetVersion) {
        jdbc.update("INSERT INTO roles (id, tenant_id, name, built_in, grants, preset_code, preset_version) VALUES (?,?,?,?,?::jsonb,?,?)",
                UUID.randomUUID(), tenantId, name, builtIn, grantsJson, presetCode, presetVersion);
    }

    @Override
    public int copyRoles(UUID tenantId, List<String> rowsJson) {
        int n = 0;
        for (String row : rowsJson) {
            n += jdbc.update("INSERT INTO roles SELECT r.* FROM (SELECT ?::jsonb AS j, ?::uuid AS tenant) src, "
                    + "jsonb_populate_record(NULL::roles, src.j || jsonb_build_object('id', gen_random_uuid(), 'tenant_id', src.tenant, "
                    + "'created_at', now(), 'updated_at', now())) r ON CONFLICT DO NOTHING", row, tenantId);
        }
        return n;
    }

    @Override
    public void deleteCopiedRoles(UUID tenantId) {
        jdbc.update("DELETE FROM roles WHERE tenant_id = ? AND NOT built_in", tenantId);
    }

    @Override
    public void deleteSeededRoles(UUID tenantId) {
        jdbc.update("DELETE FROM roles WHERE tenant_id = ? AND (built_in = true OR preset_code IS NOT NULL)", tenantId);
    }

    @Override
    public void deleteStaff(UUID tenantId) {
        jdbc.update("DELETE FROM staff WHERE tenant_id = ?", tenantId);
    }
}
