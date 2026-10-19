package com.nabd.hms.platform.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.platform.audit.AuditSearchModels.AuditEntry;
import static com.nabd.hms.platform.audit.AuditSearchModels.AuditFilter;
import static com.nabd.hms.platform.audit.AuditSearchModels.OperatorInfo;

/** Goes through search_audit_log (V16, actor-filter overload V85), a SECURITY DEFINER escape hatch from
 * audit_log's normal per-tenant RLS — this is the one place in the app that legitimately reads across tenants. */
@Repository
class AuditSearchRepository {

    private final JdbcTemplate jdbc;

    AuditSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** PLT-056: nabd_app holds no EXECUTE on search_audit_log (V1410); it is a NOINHERIT member of nabd_platform
     * and switches to it for this one statement only, on one pinned connection, then switches back. */
    List<AuditEntry> search(AuditFilter f, Long afterId, int limit) {
        return jdbc.execute((java.sql.Connection con) -> {
            try (java.sql.Statement st = con.createStatement()) {
                st.execute("SET ROLE nabd_platform");
            }
            try {
                return new JdbcTemplate(new SingleConnectionDataSource(con, true)).query("""
                        SELECT * FROM search_audit_log(
                            ?::uuid, ?::text, ?::text, ?::timestamptz, ?::timestamptz,
                            ?::uuid, ?::text, ?::text, ?::text, ?::bigint, ?::int)
                        """,
                        mapper(),
                        f.tenantId(), f.action(), f.entityType(),
                        f.createdAfter() == null ? null : Timestamp.from(f.createdAfter()),
                        f.createdBefore() == null ? null : Timestamp.from(f.createdBefore()),
                        f.actorId(), f.actorType(), f.actorRole(), f.actorName(),
                        afterId, limit);
            } finally {
                try (java.sql.Statement st = con.createStatement()) {
                    st.execute("RESET ROLE");
                } catch (java.sql.SQLException e) {
                    if (con.getAutoCommit()) throw e; // in an aborted transaction the rollback reverts SET ROLE
                }
            }
        });
    }

    /** PLT-056: one row per operator audit search/export. No PHI in params (callers pass filter values only). */
    void insertOperatorAudit(UUID operatorId, String action, UUID targetTenantId, String paramsJson, int rowCount,
                             String reason, String ip) {
        jdbc.update("""
                INSERT INTO master.operator_audit_log (operator_id, action, target_tenant_id, params, row_count, reason, ip)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?::inet)
                """, operatorId, action, targetTenantId, paramsJson, rowCount, reason, ip);
    }

    boolean tenantExists(UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM tenants WHERE id = ?)", Boolean.class, tenantId));
    }

    // master.operators carries no RLS (platform-only data) — same lookup SupportAccessRepository does.
    Optional<OperatorInfo> findOperatorInfo(UUID operatorId) {
        return jdbc.query("SELECT name, role FROM master.operators WHERE id = ?",
                (rs, i) -> new OperatorInfo(rs.getString("name"), rs.getString("role")), operatorId)
                .stream().findFirst();
    }

    private RowMapper<AuditEntry> mapper() {
        return (rs, i) -> new AuditEntry(
                rs.getLong("id"),
                rs.getString("tenant_id") == null ? null : UUID.fromString(rs.getString("tenant_id")), // null = platform-scope row (V126)
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
