package com.nabd.hms.patient.internal;

import com.nabd.hms.common.TenantContext;
import com.nabd.hms.patient.api.PatientDemographics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Component
class JdbcPatientDemographics implements PatientDemographics {

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;

    JdbcPatientDemographics(JdbcTemplate jdbc, TenantContext tenantContext) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
    }

    @Override
    public Optional<Demographics> find(UUID patientId) {
        return jdbc.query("SELECT id, mrn, name, gender, dob FROM patients WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new Demographics(rs.getObject("id", UUID.class), rs.getString("mrn"), rs.getString("name"), rs.getString("gender"),
                        rs.getObject("dob", LocalDate.class)), tenantContext.current(), patientId).stream().findFirst();
    }
}
