package com.nabd.hms.clinical;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class PrescriptionPrintRepository {

    record Header(UUID prescriptionId, String status, Instant signedAt, UUID doctorId, UUID signerId, String signerRegistrationNo,
                  String clinicName, String clinicRegion, String clinicPhone, UUID patientId) {
    }

    /** CLN-023: council and uploaded signature image (a file id) are printed on the Rx PDF. */
    record Person(String name, String registrationNo, String council, UUID signatureFileId) {
    }

    /** CLN-023: the one signed-Rx token row, with the stored PDFs and the revocation. */
    record Token(String rxToken, String payloadSha256, Instant validUntil, UUID a4FileId, String a4Sha256, UUID a5FileId, String a5Sha256,
                 Instant revokedAt, String revokeReason) {
    }

    record PatientFacts(String name, java.time.LocalDate dob, String gender) {
    }

    record Item(String drugName, String brandName, String dosage, String frequency, String duration, String route, java.math.BigDecimal quantity,
                String quantityUnit, String instructions) {
    }

    private final JdbcTemplate jdbc;

    PrescriptionPrintRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** CLN-005: the signer is the stored prescriptions.signed_by (backfilled by V1222), never read from audit_log. */
    Optional<Header> findHeader(UUID tenantId, UUID queueEntryId) {
        return jdbc.query("""
                SELECT p.id, p.status, p.signed_at, p.doctor_id, p.signed_by AS signer_id, p.signed_by_registration_no, p.patient_id,
                       t.name AS clinic_name, t.region_code AS clinic_region, t.whatsapp_number AS clinic_phone
                  FROM prescriptions p JOIN tenants t ON t.id = p.tenant_id
                 WHERE p.tenant_id = ? AND p.queue_entry_id = ?
                """, (rs, i) -> new Header(UUID.fromString(rs.getString("id")), rs.getString("status"),
                rs.getTimestamp("signed_at") == null ? null : rs.getTimestamp("signed_at").toInstant(),
                rs.getString("doctor_id") == null ? null : UUID.fromString(rs.getString("doctor_id")),
                rs.getString("signer_id") == null ? null : UUID.fromString(rs.getString("signer_id")),
                rs.getString("signed_by_registration_no"),
                rs.getString("clinic_name"), rs.getString("clinic_region"), rs.getString("clinic_phone"), rs.getObject("patient_id", UUID.class)),
                tenantId, queueEntryId).stream().findFirst();
    }

    Optional<Person> findPerson(UUID tenantId, UUID staffId) {
        return jdbc.query("SELECT name, professional_registration_no, registration_council, signature_file_id FROM staff WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new Person(rs.getString("name"), rs.getString("professional_registration_no"), rs.getString("registration_council"),
                        rs.getObject("signature_file_id", UUID.class)),
                tenantId, staffId).stream().findFirst();
    }

    /** Locks the token row (FOR UPDATE) so a first PDF render, a revoke and a send run one after the other. */
    Optional<Token> lockToken(UUID tenantId, UUID prescriptionId) {
        return jdbc.query("SELECT rx_token, payload_sha256, valid_until, pdf_a4_file_id, pdf_a4_sha256, pdf_a5_file_id, pdf_a5_sha256, revoked_at, revoke_reason "
                        + "FROM prescription_rx_tokens WHERE tenant_id = ? AND prescription_id = ? FOR UPDATE",
                (rs, i) -> new Token(rs.getString("rx_token"), rs.getString("payload_sha256"), instant(rs, "valid_until"),
                        rs.getObject("pdf_a4_file_id", UUID.class), rs.getString("pdf_a4_sha256"), rs.getObject("pdf_a5_file_id", UUID.class),
                        rs.getString("pdf_a5_sha256"), instant(rs, "revoked_at"), rs.getString("revoke_reason")),
                tenantId, prescriptionId).stream().findFirst();
    }

    /** First writer wins; a concurrent issue waits on the primary key and then does nothing. */
    void insertToken(UUID tenantId, UUID prescriptionId, String token, String payloadSha256, String kid, Instant validUntil) {
        jdbc.update("INSERT INTO prescription_rx_tokens (prescription_id, tenant_id, rx_token, payload_sha256, signing_kid, valid_until) "
                        + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (prescription_id) DO NOTHING",
                prescriptionId, tenantId, token, payloadSha256, kid, validUntil == null ? null : java.sql.Timestamp.from(validUntil));
    }

    void storePdf(UUID tenantId, UUID prescriptionId, boolean a5, UUID fileId, String sha256) {
        String col = a5 ? "pdf_a5" : "pdf_a4";
        jdbc.update("UPDATE prescription_rx_tokens SET " + col + "_file_id = ?, " + col + "_sha256 = ? WHERE tenant_id = ? AND prescription_id = ? AND "
                + col + "_file_id IS NULL", fileId, sha256, tenantId, prescriptionId);
    }

    int revoke(UUID tenantId, UUID prescriptionId, String reason, UUID by, Instant at) {
        return jdbc.update("UPDATE prescription_rx_tokens SET revoked_at = ?, revoke_reason = ?, revoked_by = ? WHERE tenant_id = ? AND prescription_id = ? "
                + "AND revoked_at IS NULL", java.sql.Timestamp.from(at), reason, by, tenantId, prescriptionId);
    }

    Optional<PatientFacts> patient(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT name, dob, gender FROM patients WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new PatientFacts(rs.getString("name"), rs.getDate("dob") == null ? null : rs.getDate("dob").toLocalDate(), rs.getString("gender")),
                tenantId, patientId).stream().findFirst();
    }

    List<Item> items(UUID tenantId, UUID prescriptionId) {
        return jdbc.query("SELECT drug_name, brand_name, dosage, frequency, duration, route, quantity, quantity_unit, instructions FROM prescription_items "
                        + "WHERE tenant_id = ? AND prescription_id = ? ORDER BY display_order",
                (rs, i) -> new Item(rs.getString("drug_name"), rs.getString("brand_name"), rs.getString("dosage"), rs.getString("frequency"),
                        rs.getString("duration"), rs.getString("route"), rs.getBigDecimal("quantity"), rs.getString("quantity_unit"), rs.getString("instructions")),
                tenantId, prescriptionId);
    }

    /** DOC-R3's allergy statement captured at signing (JSON text), null for an Rx signed before V79. */
    String allergySnapshot(UUID tenantId, UUID prescriptionId) {
        return jdbc.query("SELECT allergy_snapshot::text FROM prescriptions WHERE tenant_id = ? AND id = ?", (rs, i) -> rs.getString(1), tenantId, prescriptionId)
                .stream().findFirst().orElse(null);
    }

    private static Instant instant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        java.sql.Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
