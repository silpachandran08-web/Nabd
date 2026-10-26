package com.nabd.hms.organization.bookingpolicy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class BookingPolicyRepository {

    record Row(UUID id, int version, String status, String configJson, String changeNote, UUID publishedBy, Instant publishedAt, Instant updatedAt) {
    }

    private static final String COLS = "id, version, status, config::text AS config, change_note, published_by, published_at, updated_at";

    private final JdbcTemplate jdbc;

    BookingPolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One policy writer at a time per tenant: publish and draft saves queue on this transaction lock. */
    void lock(UUID tenantId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, "booking-policy:" + tenantId);
    }

    Optional<Row> byStatus(UUID tenantId, String status) {
        return jdbc.query("SELECT " + COLS + " FROM booking_policy_versions WHERE tenant_id = ? AND status = ?", mapper(), tenantId, status)
                .stream().findFirst();
    }

    int nextVersion(UUID tenantId) {
        Integer max = jdbc.queryForObject("SELECT COALESCE(max(version), 0) FROM booking_policy_versions WHERE tenant_id = ?", Integer.class, tenantId);
        return (max == null ? 0 : max) + 1;
    }

    void upsertDraft(UUID tenantId, int version, String configJson, UUID staffId) {
        jdbc.update("INSERT INTO booking_policy_versions (tenant_id, version, status, config, created_by) VALUES (?,?,'draft',?::jsonb,?) " +
                        "ON CONFLICT (tenant_id) WHERE status = 'draft' DO UPDATE SET config = EXCLUDED.config, updated_at = now()",
                tenantId, version, configJson, staffId);
    }

    void supersedePublished(UUID tenantId) {
        jdbc.update("UPDATE booking_policy_versions SET status = 'superseded' WHERE tenant_id = ? AND status = 'published'", tenantId);
    }

    void publishDraft(UUID tenantId, UUID staffId, String changeNote) {
        jdbc.update("UPDATE booking_policy_versions SET status = 'published', published_by = ?, published_at = now(), change_note = ? " +
                "WHERE tenant_id = ? AND status = 'draft'", staffId, changeNote, tenantId);
    }

    /** Published and superseded versions, newest first, below the cursor version. */
    List<Row> history(UUID tenantId, Integer beforeVersion, int limit) {
        return jdbc.query("SELECT " + COLS + " FROM booking_policy_versions WHERE tenant_id = ? AND status IN ('published','superseded') " +
                        "AND (?::int IS NULL OR version < ?::int) ORDER BY version DESC LIMIT ?",
                mapper(), tenantId, beforeVersion, beforeVersion, limit);
    }

    private org.springframework.jdbc.core.RowMapper<Row> mapper() {
        return (rs, i) -> new Row(UUID.fromString(rs.getString("id")), rs.getInt("version"), rs.getString("status"), rs.getString("config"),
                rs.getString("change_note"), rs.getString("published_by") == null ? null : UUID.fromString(rs.getString("published_by")),
                rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
