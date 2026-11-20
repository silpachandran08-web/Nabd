package com.nabd.hms.organization.department;

import com.nabd.hms.common.ProvisioningSeeds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * PLT-036: provisioning's default department and its rollback, moved unchanged from platform's ProvisioningRepository. No
 * department_workflow_selection row is seeded: DepartmentService.resolveStatusSequence() falls back to the clinic_walkin default.
 */
@Repository
class DepartmentSeedRepository implements ProvisioningSeeds.Departments {

    private final JdbcTemplate jdbc;

    DepartmentSeedRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void seedDefault(UUID tenantId) {
        jdbc.update("INSERT INTO departments (tenant_id, name, is_default) VALUES (?,'General',true)",
                tenantId);
    }

    @Override
    public void deleteAll(UUID tenantId) {
        jdbc.update("DELETE FROM departments WHERE tenant_id = ?", tenantId);
    }
}
