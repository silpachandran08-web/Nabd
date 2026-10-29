package com.nabd.hms.patient;

import com.nabd.hms.common.TenantContext;
import com.nabd.hms.organization.api.GoLiveCheck;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** ADM-011 good to have: at least one real (not demo) patient is on file, e.g. from the import. */
@Component
class PatientsImportedGoLiveCheck implements GoLiveCheck {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    PatientsImportedGoLiveCheck(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @Override
    public boolean blocker() {
        return false;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Result evaluate() {
        boolean ok = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM patients WHERE tenant_id = ? AND NOT is_synthetic)", Boolean.class, tenant.current()));
        return new Result("patients_imported", ok, null, "Import your patient list any time after going live.");
    }
}
