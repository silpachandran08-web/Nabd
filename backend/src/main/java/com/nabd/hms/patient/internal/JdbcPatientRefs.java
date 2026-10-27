package com.nabd.hms.patient.internal;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.patient.api.PatientRefs;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.Period;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(propagation = Propagation.MANDATORY)
class JdbcPatientRefs implements PatientRefs {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final ClinicClock clock;

    JdbcPatientRefs(JdbcTemplate jdbc, TenantContext tenantContext, ClinicClock clock) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    @Override
    public String refFor(UUID patientId) {
        UUID tenant = tenantContext.current();
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        jdbc.update("INSERT INTO internal_patient_refs (tenant_id, patient_ref, patient_id) VALUES (?,?,?) ON CONFLICT (tenant_id, patient_id) DO NOTHING",
                tenant, Base64.getUrlEncoder().withoutPadding().encodeToString(raw), patientId);
        return jdbc.queryForObject("SELECT patient_ref FROM internal_patient_refs WHERE tenant_id = ? AND patient_id = ?", String.class, tenant, patientId);
    }

    @Override
    public Optional<UUID> patientIdFor(String patientRef) {
        return jdbc.query("""
                SELECT COALESCE(p.merged_into_id, p.id) FROM internal_patient_refs r
                JOIN patients p ON p.tenant_id = r.tenant_id AND p.id = r.patient_id
                WHERE r.tenant_id = ? AND r.patient_ref = ?""", (rs, i) -> rs.getObject(1, UUID.class), tenantContext.current(), patientRef)
                .stream().findFirst();
    }

    @Override
    public List<HouseholdMember> household(String phoneHash) {
        UUID tenant = tenantContext.current();
        LocalDate today = clock.today(tenant);
        String match = "encode(sha256(decode(%s.phone_digits, 'escape')), 'hex') = ?";
        record Row(UUID id, String name, LocalDate dob, UUID guardianId, boolean isGuardian) {
        }
        List<Row> rows = jdbc.query("""
                WITH hh AS (
                  SELECT p.id, p.name, p.dob, p.guardian_id FROM patients p
                  WHERE p.tenant_id = ? AND p.status = 'active' AND %s
                  UNION
                  SELECT d.id, d.name, d.dob, d.guardian_id FROM patients d JOIN patients g ON g.tenant_id = d.tenant_id AND g.id = d.guardian_id
                  WHERE d.tenant_id = ? AND d.status = 'active' AND g.status = 'active' AND %s)
                SELECT hh.id, hh.name, hh.dob, hh.guardian_id,
                       EXISTS (SELECT 1 FROM patients c WHERE c.tenant_id = ? AND c.guardian_id = hh.id AND c.status = 'active') AS is_guardian
                FROM hh ORDER BY hh.dob, hh.id LIMIT 20""".formatted(match.formatted("p"), match.formatted("g")),
                (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("name"), rs.getDate("dob").toLocalDate(),
                        rs.getObject("guardian_id", UUID.class), rs.getBoolean("is_guardian")),
                tenant, phoneHash, tenant, phoneHash, tenant);
        // IDC-012 / ACC-042: a dependant who turned 18, or a minor whose guardian link was revoked, is not part of the list
        return rows.stream().filter(r -> (r.guardianId() != null) == (Period.between(r.dob(), today).getYears() < 18)).map(r -> new HouseholdMember(refFor(r.id()), firstName(r.name()), band(Period.between(r.dob(), today).getYears()),
                r.isGuardian() ? "guardian" : r.guardianId() != null ? "dependent" : "self", r.isGuardian(), Period.between(r.dob(), today).getYears())).toList();
    }

    private static String firstName(String name) {
        String n = name == null ? "" : name.trim();
        int sp = n.indexOf(' ');
        return sp < 0 ? n : n.substring(0, sp);
    }

    static String band(int years) {
        return years < 5 ? "0-4" : years < 12 ? "5-11" : years < 18 ? "12-17" : years < 40 ? "18-39" : years < 60 ? "40-59" : "60+";
    }
}
