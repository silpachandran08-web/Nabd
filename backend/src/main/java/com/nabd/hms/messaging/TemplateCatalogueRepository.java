package com.nabd.hms.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** template_catalogue is platform reference data (no RLS); the whatsapp_templates calls are tenant-scoped (RLS). COM-013: the
 * queued rows are catalogue rows and new template versions (clinic rename), so the catalogue join is optional. */
@Repository
class TemplateCatalogueRepository {

    record Entry(String key, String language, String category, String body, List<String> examples, String purpose, int version) {
    }

    record Due(UUID id, String metaName, String language, String category, String headerText, String body,
               List<String> examples, int attempts) {
    }

    private final JdbcTemplate jdbc;

    TemplateCatalogueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static List<String> texts(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        Array a = rs.getArray(col);
        return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
    }

    /** Latest version of each key in the given languages. */
    List<Entry> latest(List<String> languages) {
        return jdbc.query("""
                SELECT DISTINCT ON (key, language) key, language, category, body, example_values, purpose, version
                  FROM template_catalogue WHERE language = ANY (?::text[]) ORDER BY key, language, version DESC
                """, (rs, i) -> new Entry(rs.getString("key"), rs.getString("language"), rs.getString("category"),
                rs.getString("body"), texts(rs, "example_values"), rs.getString("purpose"), rs.getInt("version")),
                (Object) languages.toArray(new String[0]));
    }

    boolean instantiated(UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM whatsapp_templates WHERE tenant_id = ? AND catalogue_key IS NOT NULL)", Boolean.class, tenantId));
    }

    void insertQueued(UUID tenantId, Entry e, String metaName, String header) {
        int params = (int) java.util.regex.Pattern.compile("\\{\\{\\d+}}").matcher(e.body()).results().count();
        jdbc.update("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count,
                                                status, catalogue_key, catalogue_version, purpose)
                VALUES (?,?,?,?,?,?,?,?, 'queued', ?,?,?)
                """, tenantId, e.key(), metaName, e.language(), e.category(), header, e.body(), params, e.key(), e.version(), e.purpose());
    }

    /** Cross-tenant on purpose (SECURITY DEFINER, V1881): which tenants have templates waiting. */
    List<UUID> dueTenants(int limit) {
        return jdbc.queryForList("SELECT due_catalogue_template_tenants(?)", UUID.class, limit);
    }

    List<Due> due(UUID tenantId, int limit) {
        return jdbc.query("""
                SELECT t.id, t.meta_name, t.language, t.category, t.header_text, t.body, t.submit_attempts, t.param_count,
                       COALESCE(t.example_values, c.example_values) AS example_values
                  FROM whatsapp_templates t
                  LEFT JOIN template_catalogue c ON c.key = t.catalogue_key AND c.language = t.language AND c.version = t.catalogue_version
                 WHERE t.tenant_id = ? AND t.status = 'queued' AND (t.next_attempt_at IS NULL OR t.next_attempt_at <= now())
                 ORDER BY t.created_at, t.id LIMIT ?
                """, (rs, i) -> new Due(rs.getObject("id", UUID.class), rs.getString("meta_name"), rs.getString("language"),
                rs.getString("category"), rs.getString("header_text"), rs.getString("body"),
                examplesOrPlaceholders(texts(rs, "example_values"), rs.getInt("param_count")), rs.getInt("submit_attempts")), tenantId, limit);
    }

    /** COM-013: custom templates created before V3100 kept no examples; Meta needs one per slot to review a new version.
     * shortcut: generic placeholders ("example 1") may draw a rejection; the clinic then fixes it through the edit path. */
    static List<String> examplesOrPlaceholders(List<String> examples, int slots) {
        if (examples != null && examples.size() == slots) {
            return examples;
        }
        return java.util.stream.IntStream.rangeClosed(1, slots).mapToObj(i -> "example " + i).toList();
    }

    void deleteInstantiated(UUID tenantId) {
        jdbc.update("DELETE FROM whatsapp_templates WHERE tenant_id = ? AND catalogue_key IS NOT NULL", tenantId);
    }

    void submitted(UUID tenantId, UUID id, String status, String metaTemplateId) {
        jdbc.update("UPDATE whatsapp_templates SET status = ?, meta_template_id = ?, next_attempt_at = NULL WHERE tenant_id = ? AND id = ?",
                status, metaTemplateId, tenantId, id);
    }

    void retryLater(UUID tenantId, UUID id, int attempts, Instant at) {
        jdbc.update("UPDATE whatsapp_templates SET submit_attempts = ?, next_attempt_at = ? WHERE tenant_id = ? AND id = ?",
                attempts, java.sql.Timestamp.from(at), tenantId, id);
    }

    void refused(UUID tenantId, UUID id, String reason) {
        jdbc.update("UPDATE whatsapp_templates SET status = 'rejected', rejection_reason = ? WHERE tenant_id = ? AND id = ?",
                reason, tenantId, id);
    }
}
