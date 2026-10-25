package com.nabd.hms.patient;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** ACC-029: patients holding an E.164 phone, plus the patients whose guardian holds it (idx_patients_phone, idx_patients_guardian). */
@Repository
class HouseholdRepository {

    record Row(UUID id, String name, LocalDate dob, String gender, UUID guardianId) {
    }

    private final JdbcTemplate jdbc;

    HouseholdRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Row> find(UUID tenantId, String e164, UUID scopedToDoctorId, int limit) {
        return jdbc.query("SELECT id, name, dob, gender, guardian_id FROM patients WHERE tenant_id = ? AND status = 'active' " +
                        "AND (phone = ? OR guardian_id IN (SELECT id FROM patients WHERE tenant_id = ? AND status = 'active' AND phone = ?)) " +
                        PatientRepository.CONSENT_GATE + PatientRepository.SCOPE_GATE + "ORDER BY dob, id LIMIT ?",
                (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("name"), rs.getDate("dob").toLocalDate(),
                        rs.getString("gender"), rs.getObject("guardian_id", UUID.class)),
                tenantId, e164, tenantId, e164, scopedToDoctorId, scopedToDoctorId, limit);
    }
}
