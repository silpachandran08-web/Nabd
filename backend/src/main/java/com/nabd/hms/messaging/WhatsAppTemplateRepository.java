package com.nabd.hms.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** whatsapp_templates (V47). RLS-scoped except applyUpdate — callers set TenantContext first. */
@Repository
class WhatsAppTemplateRepository {

    record Template(UUID id, String name, String metaName, String language, String category, String headerText,
                    String body, int paramCount, String status, String rejectionReason, String qualityRating,
                    String metaTemplateId, Instant updatedAt) {

        boolean sendable() {
            return "approved".equals(status) || "flagged".equals(status);
        }
    }

    record Clinic(String slug, String name) {
    }

    private static final RowMapper<Template> MAPPER = (rs, i) -> new Template(
            rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("meta_name"), rs.getString("language"),
            rs.getString("category"), rs.getString("header_text"), rs.getString("body"), rs.getInt("param_count"),
            rs.getString("status"), rs.getString("rejection_reason"), rs.getString("quality_rating"),
            rs.getString("meta_template_id"), rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    WhatsAppTemplateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** tenants carries no RLS (see V6). */
    Clinic clinic(UUID tenantId) {
        return jdbc.queryForObject("SELECT slug, name FROM tenants WHERE id = ?",
                (rs, i) -> new Clinic(rs.getString("slug"), rs.getString("name")), tenantId);
    }

    List<Template> list(UUID tenantId) {
        return jdbc.query("SELECT * FROM whatsapp_templates WHERE tenant_id = ? ORDER BY name, language", MAPPER, tenantId);
    }

    Optional<Template> find(UUID tenantId, UUID id) {
        return jdbc.query("SELECT * FROM whatsapp_templates WHERE tenant_id = ? AND id = ?", MAPPER, tenantId, id)
                .stream().findFirst();
    }

    Optional<Template> findByName(UUID tenantId, String name, String language) {
        return jdbc.query("SELECT * FROM whatsapp_templates WHERE tenant_id = ? AND name = ? AND language = ?",
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

    void resubmitted(UUID tenantId, UUID id, String headerText, String body, int paramCount) {
        jdbc.update("""
                UPDATE whatsapp_templates SET header_text = ?, body = ?, param_count = ?, status = 'pending',
                                              rejection_reason = NULL
                WHERE tenant_id = ? AND id = ?
                """, headerText, body, paramCount, tenantId, id);
    }

    /** Cross-tenant by design — see apply_whatsapp_template_update in V47. */
    void applyUpdate(String metaTemplateId, String status, String reason, String quality) {
        jdbc.queryForList("SELECT apply_whatsapp_template_update(?, ?, ?, ?)", metaTemplateId, status, reason, quality);
    }
}
