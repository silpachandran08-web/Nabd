package com.nabd.hms.platform.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.nabd.hms.platform.audit.AuditSearchModels.AuditEntry;

/** Goes through search_audit_log (V16), a SECURITY DEFINER escape hatch from audit_log's normal
 * per-tenant RLS — this is the one place in the app that legitimately reads across tenants. */
@Repository
class AuditSearchRepository {

    private final JdbcTemplate jdbc;

    AuditSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<AuditEntry> search(UUID tenantId, String action, String entityType, Instant createdAfter,
                             Instant createdBefore, Long afterId, int limit) {
        return jdbc.query("""
                SELECT * FROM search_audit_log(
                    ?::uuid, ?::text, ?::text, ?::timestamptz, ?::timestamptz, ?::bigint, ?::int)
                """,
                mapper(),
                tenantId, action, entityType,
                createdAfter == null ? null : Timestamp.from(createdAfter),
                createdBefore == null ? null : Timestamp.from(createdBefore),
                afterId, limit);
    }

    private RowMapper<AuditEntry> mapper() {
        return (rs, i) -> new AuditEntry(
                rs.getLong("id"),
                UUID.fromString(rs.getString("tenant_id")),
                rs.getString("tenant_name"),
                rs.getString("tenant_slug"),
                rs.getString("actor_type"),
                rs.getString("actor_id") == null ? null : UUID.fromString(rs.getString("actor_id")),
                rs.getString("actor_name"),
                rs.getString("actor_role"),
                rs.getString("ip_address"),
                rs.getString("action"),
                rs.getString("entity_type"),
                rs.getString("entity_id") == null ? null : UUID.fromString(rs.getString("entity_id")),
                rs.getString("before"),
                rs.getString("after"),
                rs.getTimestamp("created_at").toInstant());
    }
}
