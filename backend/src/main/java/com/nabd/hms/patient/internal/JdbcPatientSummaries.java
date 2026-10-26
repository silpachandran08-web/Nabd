package com.nabd.hms.patient.internal;

import com.nabd.hms.common.TenantContext;
import com.nabd.hms.patient.api.PatientSummaries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
class JdbcPatientSummaries implements PatientSummaries {

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;

    JdbcPatientSummaries(JdbcTemplate jdbc, TenantContext tenantContext) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
    }

    @Override
    public Optional<Summary> find(UUID patientId) {
        return jdbc.query("SELECT id, name, mrn FROM patients WHERE tenant_id = ? AND id = ? AND status = 'active'",
                (rs, i) -> new Summary(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), tenantContext.current(), patientId).stream().findFirst();
    }
}
