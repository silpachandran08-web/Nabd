package com.nabd.hms.nursing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** CLN-014: consent forms and signed consents. Runs in the caller's tenant transaction (RLS); starter rows have tenant_id NULL. */
@Repository
class ConsentRepository {

    record Template(UUID id, UUID tenantId, String chargeCode, String lang, int version, String title, String bodyHtml, boolean placeholder) {
    }

    record Consent(UUID id, UUID procedureOrderId, String status, UUID pdfFileId, String pdfSha256) {
    }

    record PatientFacts(String name, LocalDate dob, UUID guardianId) {
    }

    private static final String TEMPLATE_COLS = "id, tenant_id, charge_code, lang, version, title, body_html, placeholder";

    private final JdbcTemplate jdbc;

    ConsentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Newest active version per language; the clinic's own form wins over a starter placeholder of the same language. */
    List<Template> activeLatest(UUID tenantId, String chargeCode, String lang) {
        return jdbc.query("SELECT DISTINCT ON (lang) " + TEMPLATE_COLS + " FROM consent_templates " +
                        "WHERE (tenant_id = ? OR tenant_id IS NULL) AND charge_code = ? AND active AND (?::text IS NULL OR lang = ?::text) " +
                        "ORDER BY lang, (tenant_id IS NULL), version DESC",
                this::template, tenantId, chargeCode, lang, lang);
    }

    Optional<Template> find(UUID tenantId, UUID id) {
        return jdbc.query("SELECT " + TEMPLATE_COLS + " FROM consent_templates WHERE id = ? AND (tenant_id = ? OR tenant_id IS NULL) AND active",
                this::template, id, tenantId).stream().findFirst();
    }

    boolean clinicHasForm(UUID tenantId, String chargeCode) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM consent_templates WHERE tenant_id = ? AND charge_code = ? AND active)",
                Boolean.class, tenantId, chargeCode));
    }

    /** Next version for (clinic, code, language); a concurrent insert of the same version fails on the unique index. */
    Template insertTemplate(UUID tenantId, String chargeCode, String lang, String title, String bodyHtml, UUID createdBy) {
        return jdbc.queryForObject("INSERT INTO consent_templates (tenant_id, charge_code, lang, version, title, body_html, created_by) " +
                        "SELECT ?, ?, ?, COALESCE(MAX(version), 0) + 1, ?, ?, ? FROM consent_templates WHERE tenant_id = ? AND charge_code = ? AND lang = ? " +
                        "RETURNING " + TEMPLATE_COLS,
                this::template, tenantId, chargeCode, lang, title, bodyHtml, createdBy, tenantId, chargeCode, lang);
    }

    Optional<PatientFacts> patient(UUID tenantId, UUID patientId) {
        return jdbc.query("SELECT name, dob, guardian_id FROM patients WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new PatientFacts(rs.getString("name"), rs.getObject("dob", LocalDate.class), rs.getObject("guardian_id", UUID.class)),
                tenantId, patientId).stream().findFirst();
    }

    String clinicName(UUID tenantId) {
        return jdbc.query("SELECT name FROM tenants WHERE id = ?", (rs, i) -> rs.getString(1), tenantId).stream().findFirst().orElse("");
    }

    void insertConsent(UUID id, UUID tenantId, UUID patientId, UUID orderId, Template t, String signerKind, UUID guardianId, String signerName,
                       UUID witnessId, UUID signatureFileId, UUID pdfFileId, String sha256, UUID signedBy, Instant signedAt) {
        jdbc.update("INSERT INTO procedure_consents (id, tenant_id, patient_id, procedure_order_id, template_id, template_version, lang, signer_kind, " +
                        "guardian_id, signer_name, witness_staff_id, signature_file_id, pdf_file_id, pdf_sha256, signed_by, signed_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, tenantId, patientId, orderId, t.id(), t.version(), t.lang(), signerKind, guardianId, signerName, witnessId, signatureFileId, pdfFileId,
                sha256, signedBy, java.sql.Timestamp.from(signedAt));
    }

    Optional<Consent> findConsent(UUID tenantId, UUID id) {
        return jdbc.query("SELECT id, procedure_order_id, status, pdf_file_id, pdf_sha256 FROM procedure_consents WHERE tenant_id = ? AND id = ?",
                this::consent, tenantId, id).stream().findFirst();
    }

    Optional<Consent> findForOrder(UUID tenantId, UUID orderId) {
        return jdbc.query("SELECT id, procedure_order_id, status, pdf_file_id, pdf_sha256 FROM procedure_consents WHERE tenant_id = ? AND procedure_order_id = ?",
                this::consent, tenantId, orderId).stream().findFirst();
    }

    void withdraw(UUID tenantId, UUID orderId, UUID staffId, String reason) {
        jdbc.update("UPDATE procedure_consents SET status = 'withdrawn', withdrawn_at = now(), withdrawn_by = ?, withdraw_reason = ? " +
                "WHERE tenant_id = ? AND procedure_order_id = ? AND status = 'signed'", staffId, reason, tenantId, orderId);
    }

    private Template template(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Template(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("charge_code"), rs.getString("lang"),
                rs.getInt("version"), rs.getString("title"), rs.getString("body_html"), rs.getBoolean("placeholder"));
    }

    private Consent consent(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Consent(rs.getObject("id", UUID.class), rs.getObject("procedure_order_id", UUID.class), rs.getString("status"),
                rs.getObject("pdf_file_id", UUID.class), rs.getString("pdf_sha256"));
    }
}
