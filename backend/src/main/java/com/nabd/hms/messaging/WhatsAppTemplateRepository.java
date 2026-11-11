package com.nabd.hms.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** whatsapp_templates (V47; versions V3100). RLS-scoped except applyUpdate, tenantOfMeta and staleHeaders — callers bind the
 * tenant first. COM-013: a (tenant, name, language) is a group of versions; exactly one row is active and sends use it. */
@Repository
class WhatsAppTemplateRepository {

    record Template(UUID id, String name, String metaName, String language, String category, String headerText,
                    String body, int paramCount, String status, String rejectionReason, String qualityRating,
                    String metaTemplateId, Instant updatedAt, String catalogueKey, int version, boolean active,
                    String metaCategory, Instant pausedUntil, Instant categoryChangedAt, List<String> examples,
                    Integer catalogueVersion, String purpose, Instant createdAt) {

        boolean sendable() {
            return "approved".equals(status) || "flagged".equals(status);
        }

        /** COM-013: Meta's category wins once it re-categorised the template (consent class and price follow it). */
        String effectiveCategory() {
            return metaCategory != null ? metaCategory : category;
        }

        /** COM-013: a paused template still takes new sends; the send handler holds them until the pause lifts (or the
         * send window ends). */
        boolean queueable() {
            return sendable() || "paused".equals(status);
        }

        boolean inReview() {
            return "submitting".equals(status) || "queued".equals(status) || "pending".equals(status);
        }
    }

    record Clinic(String slug, String name) {
    }

    record Ref(UUID tenantId, UUID id) {
    }

    private static final RowMapper<Template> MAPPER = (rs, i) -> new Template(
            rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("meta_name"), rs.getString("language"),
            rs.getString("category"), rs.getString("header_text"), rs.getString("body"), rs.getInt("param_count"),
            rs.getString("status"), rs.getString("rejection_reason"), rs.getString("quality_rating"),
            rs.getString("meta_template_id"), rs.getTimestamp("updated_at").toInstant(), rs.getString("catalogue_key"),
            rs.getInt("version"), rs.getBoolean("active"), rs.getString("meta_category"), instant(rs, "paused_until"),
            instant(rs, "category_changed_at"), texts(rs, "example_values"), (Integer) rs.getObject("catalogue_version"),
            rs.getString("purpose"), rs.getTimestamp("created_at").toInstant());

    private static Instant instant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static List<String> texts(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        java.sql.Array a = rs.getArray(col);
        return a == null ? null : Arrays.asList((String[]) a.getArray());
    }

    private static String[] array(List<String> l) {
        return l == null ? null : l.toArray(new String[0]);
    }

    private final JdbcTemplate jdbc;

    WhatsAppTemplateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** tenants carries no RLS (see V6). */
    Clinic clinic(UUID tenantId) {
        return jdbc.queryForObject("SELECT slug, name FROM tenants WHERE id = ?",
                (rs, i) -> new Clinic(rs.getString("slug"), rs.getString("name")), tenantId);
    }

    /** Every version, newest first within a name and language. */
    List<Template> list(UUID tenantId) {
        return jdbc.query("SELECT * FROM whatsapp_templates WHERE tenant_id = ? ORDER BY name, language, version DESC", MAPPER, tenantId);
    }

    /** COM-013: all versions of one template, newest first; FOR UPDATE so two edits of the same template serialize. */
    List<Template> groupForUpdate(UUID tenantId, String name, String language) {
        return jdbc.query("SELECT * FROM whatsapp_templates WHERE tenant_id = ? AND name = ? AND language = ? ORDER BY version DESC FOR UPDATE",
                MAPPER, tenantId, name, language);
    }

    Optional<Template> find(UUID tenantId, UUID id) {
        return jdbc.query("SELECT * FROM whatsapp_templates WHERE tenant_id = ? AND id = ?", MAPPER, tenantId, id)
                .stream().findFirst();
    }

    /** The active version: what every send by name uses. */
    Optional<Template> findByName(UUID tenantId, String name, String language) {
        return jdbc.query("SELECT * FROM whatsapp_templates WHERE tenant_id = ? AND name = ? AND language = ? AND active",
                MAPPER, tenantId, name, language).stream().findFirst();
    }

    UUID insert(UUID tenantId, String name, String metaName, String language, String category, String headerText,
                String body, int paramCount, String status, String metaTemplateId) {
        return jdbc.queryForObject("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body,
                                                param_count, status, meta_template_id)
                VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id
                """, UUID.class, tenantId, name, metaName, language, category, headerText, body, paramCount, status,
                metaTemplateId);
    }

    /** COM-003 phase 1: the row exists (and the name is taken) before Meta is called. */
    UUID insertSubmitting(UUID tenantId, String name, String metaName, String language, String category, String headerText,
                          String body, int paramCount, List<String> examples) {
        return jdbc.queryForObject("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body,
                                                param_count, status, submitted_at, example_values)
                VALUES (?,?,?,?,?,?,?,?,'submitting', now(), ?) RETURNING id
                """, UUID.class, tenantId, name, metaName, language, category, headerText, body, paramCount, array(examples));
    }

    /** COM-013: the next version of {@code src}'s template, inactive until Meta approves it. {@code status} is 'submitting'
     * (the caller hands it to Meta now) or 'queued' (CatalogueSubmissionJob hands it over). */
    UUID insertVersion(UUID tenantId, Template src, int version, String metaName, String headerText, String body,
                       List<String> examples, String status) {
        return jdbc.queryForObject("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count,
                                                status, submitted_at, example_values, version, active, catalogue_key,
                                                catalogue_version, purpose)
                VALUES (?,?,?,?,?,?,?,?,?, CASE WHEN ? = 'submitting' THEN now() END, ?, ?, false, ?,?,?) RETURNING id
                """, UUID.class, tenantId, src.name(), metaName, src.language(), src.effectiveCategory(), headerText, body,
                src.paramCount(), status, status, array(examples), version, src.catalogueKey(), src.catalogueVersion(), src.purpose());
    }

    /** COM-003 phase 3: Meta's answer. Does nothing when the row is no longer 'submitting' (the reconciler got there first). */
    int completeSubmission(UUID tenantId, UUID id, String status, String metaTemplateId) {
        return jdbc.update("UPDATE whatsapp_templates SET status = ?, meta_template_id = ? WHERE tenant_id = ? AND id = ? AND status = 'submitting'",
                status, metaTemplateId, tenantId, id);
    }

    int deleteSubmitting(UUID tenantId, UUID id) {
        return jdbc.update("DELETE FROM whatsapp_templates WHERE tenant_id = ? AND id = ? AND status = 'submitting'", tenantId, id);
    }

    record Stale(UUID tenantId, UUID id, String metaName, String language) {
    }

    /** Cross-tenant by design — see stale_submitting_templates in V1861. */
    List<Stale> staleSubmitting(int minutes, int limit) {
        return jdbc.query("SELECT * FROM stale_submitting_templates(?, ?)", (rs, i) -> new Stale(
                rs.getObject("tenant_id", UUID.class), rs.getObject("id", UUID.class), rs.getString("meta_name"),
                rs.getString("language")), minutes, limit);
    }

    /** A version Meta has never seen ('queued') takes the new clinic name in place. */
    void setHeader(UUID tenantId, UUID id, String headerText) {
        jdbc.update("UPDATE whatsapp_templates SET header_text = ? WHERE tenant_id = ? AND id = ? AND status = 'queued'", headerText, tenantId, id);
    }

    /** Cross-tenant by design — see whatsapp_template_tenant in V3100. */
    Optional<UUID> tenantOfMeta(String metaTemplateId) {
        return Optional.ofNullable(jdbc.queryForObject("SELECT whatsapp_template_tenant(?)", UUID.class, metaTemplateId));
    }

    Optional<Template> findByMetaForUpdate(UUID tenantId, String metaTemplateId) {
        return jdbc.query("SELECT * FROM whatsapp_templates WHERE tenant_id = ? AND meta_template_id = ? FOR UPDATE", MAPPER,
                tenantId, metaTemplateId).stream().findFirst();
    }

    /** A review verdict or pause: the reason is kept only while rejected, paused_until only while paused. */
    void applyStatus(UUID tenantId, UUID id, String status, String reason, Instant pausedUntil) {
        jdbc.update("""
                UPDATE whatsapp_templates SET status = ?,
                       rejection_reason = CASE WHEN ? = 'rejected' THEN COALESCE(?, rejection_reason) END,
                       paused_until = CASE WHEN ? = 'paused' THEN ?::timestamptz END
                WHERE tenant_id = ? AND id = ?
                """, status, status, reason, status, pausedUntil == null ? null : Timestamp.from(pausedUntil), tenantId, id);
    }

    void applyQuality(UUID tenantId, UUID id, String quality) {
        jdbc.update("UPDATE whatsapp_templates SET quality_rating = ? WHERE tenant_id = ? AND id = ?", quality, tenantId, id);
    }

    void applyCategory(UUID tenantId, UUID id, String metaCategory) {
        jdbc.update("UPDATE whatsapp_templates SET meta_category = ?, category_changed_at = now() WHERE tenant_id = ? AND id = ?",
                metaCategory, tenantId, id);
    }

    /** COM-013 swap, in the caller's transaction: the old active row steps down first (one active row per name). */
    void swapActive(UUID tenantId, UUID oldId, UUID newId) {
        jdbc.update("UPDATE whatsapp_templates SET active = false, superseded_by = ? WHERE tenant_id = ? AND id = ?", newId, tenantId, oldId);
        jdbc.update("UPDATE whatsapp_templates SET active = true, superseded_by = NULL WHERE tenant_id = ? AND id = ?", tenantId, newId);
    }

    /** An older version approved after a newer one took over: it stays inactive, pointing at the active row. */
    void markSuperseded(UUID tenantId, UUID id, UUID by) {
        jdbc.update("UPDATE whatsapp_templates SET superseded_by = ? WHERE tenant_id = ? AND id = ? AND NOT active", by, tenantId, id);
    }

    /** Cross-tenant by design — see templates_with_stale_header in V3100. */
    List<Ref> staleHeaders(int limit) {
        return jdbc.query("SELECT * FROM templates_with_stale_header(?)", (rs, i) -> new Ref(rs.getObject("tenant_id", UUID.class),
                rs.getObject("id", UUID.class)), limit);
    }

    /** Cross-tenant by design — see apply_whatsapp_template_update in V47. */
    void applyUpdate(String metaTemplateId, String status, String reason, String quality) {
        jdbc.queryForList("SELECT apply_whatsapp_template_update(?, ?, ?, ?)", metaTemplateId, status, reason, quality);
    }
}
