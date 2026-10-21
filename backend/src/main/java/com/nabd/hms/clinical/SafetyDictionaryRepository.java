package com.nabd.hms.clinical;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** CLN-004: safety_dictionary_* are regional reference tables (no tenant_id, no RLS); nabd_app may only SELECT them and
 * changes go through the V1210 SECURITY DEFINER functions. */
@Repository
class SafetyDictionaryRepository {

    record VersionRow(UUID id, String region, int version, String status, String checksum, String labelsJson,
                      String csoApprovalRef, String approvedBy, Instant approvedAt, Instant activatedAt, Instant createdAt) {
    }

    record ImportResult(UUID versionId, int version, String checksum, String status, boolean created) {
    }

    record ActivateResult(String outcome, String region, Integer version, Integer previousVersion) {
    }

    private static final String VERSION_SELECT = "SELECT id, region, version, status, checksum, labels::text AS labels_json, " +
            "cso_approval_ref, approved_by, approved_at, activated_at, created_at FROM safety_dictionary_versions ";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    SafetyDictionaryRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** region -> active version number, for the cheap per-pod refresh poll. */
    Map<String, Integer> activeVersions() {
        Map<String, Integer> out = new java.util.HashMap<>();
        jdbc.query("SELECT region, version FROM safety_dictionary_versions WHERE status = 'active'",
                rs -> {
                    out.put(rs.getString("region"), rs.getInt("version"));
                });
        return out;
    }

    Optional<VersionRow> findActive(String region) {
        return jdbc.query(VERSION_SELECT + "WHERE region = ? AND status = 'active'", (rs, i) -> map(rs), region).stream().findFirst();
    }

    Optional<VersionRow> find(String region, UUID versionId) {
        return jdbc.query(VERSION_SELECT + "WHERE region = ? AND id = ?", (rs, i) -> map(rs), region, versionId).stream().findFirst();
    }

    List<VersionRow> list(String region) {
        return jdbc.query(VERSION_SELECT + "WHERE region = ? ORDER BY version DESC", (rs, i) -> map(rs), region);
    }

    List<SafetyDictionary.Entry> entries(UUID versionId) {
        return jdbc.query("SELECT kind, class_id, match_kind, value, lang, cross_reacts_to FROM safety_dictionary_entries " +
                        "WHERE version_id = ? ORDER BY id",
                (rs, i) -> new SafetyDictionary.Entry(rs.getString("kind"), rs.getString("class_id"), rs.getString("match_kind"),
                        rs.getString("value"), rs.getString("lang"), strings(rs.getArray("cross_reacts_to"))),
                versionId);
    }

    ImportResult importVersion(String region, List<SafetyDictionary.Entry> entries, Map<String, Map<String, String>> labels, String createdBy) {
        return jdbc.queryForObject("SELECT * FROM safety_dictionary_import(?, ?::jsonb, ?::jsonb, ?)",
                (rs, i) -> new ImportResult(UUID.fromString(rs.getString("version_id")), rs.getInt("version"), rs.getString("checksum"),
                        rs.getString("status"), rs.getBoolean("created")),
                region, json(entries), json(labels), createdBy);
    }

    boolean approve(UUID versionId, String csoApprovalRef, String approvedBy) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT safety_dictionary_approve(?, ?, ?)", Boolean.class,
                versionId, csoApprovalRef, approvedBy));
    }

    ActivateResult activate(UUID versionId) {
        return jdbc.queryForObject("SELECT * FROM safety_dictionary_activate(?)",
                (rs, i) -> new ActivateResult(rs.getString("outcome"), rs.getString("region"),
                        (Integer) rs.getObject("version"), (Integer) rs.getObject("previous_version")),
                versionId);
    }

    /** labels.controlled of a version as lang -> text. */
    Map<String, String> controlledLabel(VersionRow v) {
        try {
            Map<String, Map<String, String>> all = objectMapper.readValue(v.labelsJson(), new TypeReference<>() {
            });
            Map<String, String> c = all.get("controlled");
            return c == null ? Map.of() : c;
        } catch (Exception e) {
            throw new IllegalStateException("unreadable labels on safety dictionary version " + v.id(), e);
        }
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalArgumentException("not serialisable", e);
        }
    }

    private static List<String> strings(Array a) throws SQLException {
        return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
    }

    private static VersionRow map(ResultSet rs) throws SQLException {
        return new VersionRow(UUID.fromString(rs.getString("id")), rs.getString("region"), rs.getInt("version"),
                rs.getString("status"), rs.getString("checksum"), rs.getString("labels_json"), rs.getString("cso_approval_ref"),
                rs.getString("approved_by"), instant(rs, "approved_at"), instant(rs, "activated_at"), instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        java.sql.Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
