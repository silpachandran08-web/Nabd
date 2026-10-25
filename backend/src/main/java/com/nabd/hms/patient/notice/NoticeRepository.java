package com.nabd.hms.patient.notice;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** IDC-003 SQL for privacy_notices (tenant, RLS) and notice_templates (platform reference data). */
@Repository
class NoticeRepository {

    record Template(String key, String language, String bodyMd, List<String> sections, boolean legalReviewed) {
    }

    record Notice(UUID id, String version, String language, String templateKey, String bodyMd, String mandatoryHash,
                  OffsetDateTime effectiveFrom, boolean legalReviewed) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    NoticeRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** The clinic's market (region) and name, and the grievance contact block. */
    record Clinic(String region, String name, String contactName, String contactEmail, String contactPhone) {
    }

    Clinic clinic(UUID tenantId) {
        return jdbc.queryForObject("SELECT region_code, name, consent_contact_name, consent_contact_email, consent_contact_phone FROM tenants WHERE id = ?",
                (rs, i) -> new Clinic(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), tenantId);
    }

    /** Newest template version per language of a market. */
    List<Template> latestTemplates(String market) {
        return jdbc.query("SELECT DISTINCT ON (language) key, language, body_md, mandatory_sections::text, legal_reviewed FROM notice_templates " +
                "WHERE market = ? ORDER BY language, version DESC", (rs, i) -> template(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5)), market);
    }

    Optional<Template> template(String key, String language) {
        return jdbc.query("SELECT key, language, body_md, mandatory_sections::text, legal_reviewed FROM notice_templates WHERE key = ? AND language = ?",
                (rs, i) -> template(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5)), key, language).stream().findFirst();
    }

    private Template template(String key, String language, String body, String sections, boolean reviewed) {
        return new Template(key, language, body, json.readValue(sections, new TypeReference<List<String>>() { }), reviewed);
    }

    boolean hasAnyNotice() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM privacy_notices WHERE tenant_id = app_tenant_id())", Boolean.class));
    }

    /** The highest numeric version of a language, or empty (non-numeric legacy labels are ignored). */
    Optional<Integer> currentNumericVersion(String language) {
        return Optional.ofNullable(jdbc.queryForObject("SELECT max(version::int) FROM privacy_notices WHERE tenant_id = app_tenant_id() AND language = ? AND version ~ '^[0-9]{1,9}$'",
                Integer.class, language));
    }

    UUID insert(UUID tenantId, String version, String language, String templateKey, String bodyMd, String hash, UUID createdBy) {
        return jdbc.queryForObject("INSERT INTO privacy_notices (tenant_id, version, language, template_key, body_md, mandatory_hash, created_by) " +
                "VALUES (?,?,?,?,?,?,?) RETURNING id", UUID.class, tenantId, version, language, templateKey, bodyMd, hash, createdBy);
    }

    private static final String SELECT = "SELECT n.id, n.version, n.language, n.template_key, n.body_md, n.mandatory_hash, n.effective_from, " +
            "COALESCE(t.legal_reviewed, false) FROM privacy_notices n LEFT JOIN notice_templates t ON t.key = n.template_key AND t.language = n.language WHERE n.tenant_id = app_tenant_id() ";

    private static Notice notice(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Notice(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getObject(7, OffsetDateTime.class), rs.getBoolean(8));
    }

    List<Notice> all() {
        return jdbc.query(SELECT + "ORDER BY n.language, n.created_at DESC, n.id", NoticeRepository::notice);
    }

    Optional<Notice> find(String version, String language) {
        return jdbc.query(SELECT + "AND n.version = ? AND n.language = ?", NoticeRepository::notice, version, language).stream().findFirst();
    }

    /** Newest notice in force for a language. */
    Optional<Notice> current(String language) {
        return jdbc.query(SELECT + "AND n.language = ? AND n.effective_from <= now() ORDER BY n.effective_from DESC, n.created_at DESC LIMIT 1",
                NoticeRepository::notice, language).stream().findFirst();
    }

    /** Newest data_processing consent row of the patient: {notice_version, language, granted}, or empty. */
    record Accepted(String version, String language, boolean granted) {
    }

    Optional<Accepted> latestConsent(UUID patientId) {
        return jdbc.query("SELECT notice_version, language, withdrawn_at IS NULL FROM consents WHERE tenant_id = app_tenant_id() AND patient_id = ? AND consent_type = 'data_processing' " +
                "ORDER BY created_at DESC, id DESC LIMIT 1", (rs, i) -> new Accepted(rs.getString(1), rs.getString(2), rs.getBoolean(3)), patientId).stream().findFirst();
    }

    boolean patientExists(UUID patientId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM patients WHERE tenant_id = app_tenant_id() AND id = ?)", Boolean.class, patientId));
    }

    void insertAck(UUID tenantId, UUID patientId, String version, String language, UUID staffId) {
        int v = jdbc.queryForObject("SELECT COALESCE(max(version), 0) + 1 FROM consents WHERE tenant_id = app_tenant_id() AND patient_id = ? AND consent_type = 'data_processing'", Integer.class, patientId);
        jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, version, notice_version, language, channel, captured_by) " +
                "VALUES (?,?,'data_processing',?,?,?,'DESK',?)", tenantId, patientId, v, version, language, staffId);
    }
}
