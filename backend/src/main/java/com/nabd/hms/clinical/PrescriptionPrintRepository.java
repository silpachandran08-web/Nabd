package com.nabd.hms.clinical;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
class PrescriptionPrintRepository {

    record Header(UUID prescriptionId, String status, Instant signedAt, UUID doctorId, UUID signerId, String signerRegistrationNo,
                  String clinicName, String clinicRegion, String clinicPhone) {
    }

    record Person(String name, String registrationNo) {
    }

    private final JdbcTemplate jdbc;

    PrescriptionPrintRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** CLN-005: the signer is the stored prescriptions.signed_by (backfilled by V1222), never read from audit_log. */
    Optional<Header> findHeader(UUID tenantId, UUID queueEntryId) {
        return jdbc.query("""
                SELECT p.id, p.status, p.signed_at, p.doctor_id, p.signed_by AS signer_id, p.signed_by_registration_no,
                       t.name AS clinic_name, t.region AS clinic_region, t.whatsapp_number AS clinic_phone
                  FROM prescriptions p JOIN tenants t ON t.id = p.tenant_id
                 WHERE p.tenant_id = ? AND p.queue_entry_id = ?
                """, (rs, i) -> new Header(UUID.fromString(rs.getString("id")), rs.getString("status"),
                rs.getTimestamp("signed_at") == null ? null : rs.getTimestamp("signed_at").toInstant(),
                rs.getString("doctor_id") == null ? null : UUID.fromString(rs.getString("doctor_id")),
                rs.getString("signer_id") == null ? null : UUID.fromString(rs.getString("signer_id")),
                rs.getString("signed_by_registration_no"),
                rs.getString("clinic_name"), rs.getString("clinic_region"), rs.getString("clinic_phone")),
                tenantId, queueEntryId).stream().findFirst();
    }

    Optional<Person> findPerson(UUID tenantId, UUID staffId) {
        return jdbc.query("SELECT name, professional_registration_no FROM staff WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new Person(rs.getString("name"), rs.getString("professional_registration_no")),
                tenantId, staffId).stream().findFirst();
    }
}
