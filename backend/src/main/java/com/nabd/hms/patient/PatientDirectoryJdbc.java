package com.nabd.hms.patient;

import com.nabd.hms.common.TenantContext;
import com.nabd.hms.patient.api.PatientDirectory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** ADM-012: PatientDirectory over the patients table. Every method needs the caller's tenant transaction (MANDATORY). */
@Component
class PatientDirectoryJdbc implements PatientDirectory {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final com.nabd.hms.organization.api.DataGatePort gate;

    PatientDirectoryJdbc(JdbcTemplate jdbc, TenantContext tenant, com.nabd.hms.organization.api.DataGatePort gate) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.gate = gate;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Set<String> existingPhoneDobKeys(Collection<String> phonesE164) {
        Set<String> keys = new HashSet<>();
        if (phonesE164.isEmpty()) return keys;
        jdbc.query("SELECT phone, dob FROM patients WHERE tenant_id = ? AND status = 'active' AND phone = ANY (?::text[])",
                rs -> { keys.add(rs.getString("phone") + "|" + rs.getDate("dob").toLocalDate()); },
                tenant.current(), phonesE164.toArray(new String[0]));
        return keys;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> findByPhoneDob(String phoneE164, LocalDate dob) {
        return jdbc.query("SELECT id FROM patients WHERE tenant_id = ? AND status = 'active' AND phone = ? AND dob = ? ORDER BY created_at LIMIT 1",
                (rs, i) -> rs.getObject("id", UUID.class), tenant.current(), phoneE164, Date.valueOf(dob)).stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID createImported(String name, String phoneE164, LocalDate dob, String gender, String legacyRecordNo) {
        gate.requireRealData(); // ADM-002: imported patients are real patients
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender, legacy_record_no) VALUES (?,?,?,?,?,?,?)",
                id, tenant.current(), name, phoneE164, Date.valueOf(dob), gender, legacyRecordNo);
        return id;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void mergeImported(UUID patientId, String legacyRecordNo) {
        if (legacyRecordNo == null) return;
        jdbc.update("UPDATE patients SET legacy_record_no = ? WHERE tenant_id = ? AND id = ? AND legacy_record_no IS NULL",
                legacyRecordNo, tenant.current(), patientId);
    }
}
