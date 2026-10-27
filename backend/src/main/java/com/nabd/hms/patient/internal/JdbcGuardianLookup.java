package com.nabd.hms.patient.internal;

import com.nabd.hms.common.TenantContext;
import com.nabd.hms.patient.api.GuardianLookup;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(propagation = Propagation.MANDATORY)
class JdbcGuardianLookup implements GuardianLookup {

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;

    JdbcGuardianLookup(JdbcTemplate jdbc, TenantContext tenantContext) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
    }

    @Override
    public boolean isGuardianOf(UUID guardianPatientId, UUID dependantPatientId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM patients WHERE tenant_id = ? AND id = ? AND guardian_id = ?",
                Integer.class, tenantContext.current(), dependantPatientId, guardianPatientId);
        return n != null && n > 0;
    }
}
