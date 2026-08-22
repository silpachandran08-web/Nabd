package com.nabd.hms.staff;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.staff.StaffRoleModels.DelegationRow;
import static com.nabd.hms.staff.StaffRoleModels.RoleRow;

@Repository
class RoleRepository {

    private final JdbcTemplate jdbc;

    RoleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<RoleRow> list(UUID tenantId) {
        return jdbc.query(
                "SELECT id, tenant_id, name, built_in, grants::text AS grants_json, mfa_required FROM roles WHERE tenant_id = ? ORDER BY name",
                roleMapper(), tenantId);
    }

    Optional<RoleRow> findById(UUID tenantId, UUID id) {
        return jdbc.query(
                "SELECT id, tenant_id, name, built_in, grants::text AS grants_json, mfa_required FROM roles WHERE tenant_id = ? AND id = ?",
                roleMapper(), tenantId, id
        ).stream().findFirst();
    }

    void insert(UUID id, UUID tenantId, String name, String grantsJson, boolean mfaRequired) {
        jdbc.update("INSERT INTO roles (id, tenant_id, name, built_in, grants, mfa_required) VALUES (?,?,?,false,?::jsonb,?)",
                id, tenantId, name, grantsJson, mfaRequired);
    }

    void update(UUID tenantId, UUID id, String name, String grantsJson, boolean mfaRequired) {
        jdbc.update("UPDATE roles SET name = ?, grants = ?::jsonb, mfa_required = ? WHERE tenant_id = ? AND id = ? AND built_in = false",
                name, grantsJson, mfaRequired, tenantId, id);
    }

    record ActorInfo(String name, String role) {
    }

    /** NB-056/057: audit_log's actor_name/actor_role snapshot — same per-module pattern as Patient/Nursing/Packages. */
    Optional<ActorInfo> findActorInfo(UUID tenantId, UUID staffId) {
        return jdbc.query("SELECT s.name, r.name AS role_name FROM staff s JOIN roles r ON r.id = s.role_id " +
                        "WHERE s.tenant_id = ? AND s.id = ?",
                (rs, i) -> new ActorInfo(rs.getString("name"), rs.getString("role_name")),
                tenantId, staffId).stream().findFirst();
    }

    // ── delegations (NB-057) ─────────────────────────────────────────────

    private static final String DELEGATION_SELECT =
            "SELECT d.id, d.staff_id, d.delegated_role_id, r.name AS delegated_role_name, d.granted_by, " +
                    "d.reason, d.starts_at, d.expires_at, d.revoked_at, d.revoked_reason " +
                    "FROM role_delegations d JOIN roles r ON r.id = d.delegated_role_id ";

    UUID insertDelegation(UUID tenantId, UUID staffId, UUID delegatedRoleId, UUID grantedBy, String reason, Instant expiresAt) {
        return jdbc.queryForObject(
                "INSERT INTO role_delegations (tenant_id, staff_id, delegated_role_id, granted_by, reason, expires_at) " +
                        "VALUES (?,?,?,?,?,?) RETURNING id",
                UUID.class, tenantId, staffId, delegatedRoleId, grantedBy, reason, Timestamp.from(expiresAt));
    }

    List<DelegationRow> listDelegations(UUID tenantId) {
        return jdbc.query(DELEGATION_SELECT + "WHERE d.tenant_id = ? ORDER BY d.starts_at DESC", delegationMapper(), tenantId);
    }

    Optional<DelegationRow> findDelegation(UUID tenantId, UUID id) {
        return jdbc.query(DELEGATION_SELECT + "WHERE d.tenant_id = ? AND d.id = ?", delegationMapper(), tenantId, id)
                .stream().findFirst();
    }

    /** Active, not-yet-expired delegations for a staff member — what AuthService merges into a fresh token. */
    List<DelegationRow> findActiveDelegations(UUID staffId) {
        return jdbc.query(DELEGATION_SELECT +
                        "WHERE d.staff_id = ? AND d.revoked_at IS NULL AND d.starts_at <= now() AND d.expires_at > now()",
                delegationMapper(), staffId);
    }

    /**
     * Atomically closes out every delegation for this staff member that has passed its expires_at
     * but was never revoked, returning the ones just closed — the caller audits exactly those.
     * Same "detect and close on next read" shape as the account-lockout counter: no scheduler
     * exists to do this the instant it lapses, so the next token mint (max ~15 min later, the
     * access-token TTL) is what notices and logs it.
     */
    List<DelegationRow> expireStaleDelegations(UUID staffId) {
        List<UUID> ids = jdbc.query(
                "UPDATE role_delegations SET revoked_at = expires_at, revoked_reason = 'expired' " +
                        "WHERE staff_id = ? AND revoked_at IS NULL AND expires_at <= now() RETURNING id",
                (rs, i) -> UUID.fromString(rs.getString("id")), staffId);
        return ids.stream().map(id -> findDelegationNoTenantFilter(id)).toList();
    }

    private DelegationRow findDelegationNoTenantFilter(UUID id) {
        return jdbc.query(DELEGATION_SELECT + "WHERE d.id = ?", delegationMapper(), id).get(0);
    }

    void revokeDelegation(UUID tenantId, UUID id) {
        jdbc.update("UPDATE role_delegations SET revoked_at = now(), revoked_reason = 'manual' " +
                "WHERE tenant_id = ? AND id = ? AND revoked_at IS NULL", tenantId, id);
    }

    private RowMapper<DelegationRow> delegationMapper() {
        return (rs, i) -> {
            Timestamp revokedAt = rs.getTimestamp("revoked_at");
            return new DelegationRow(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("staff_id")),
                    UUID.fromString(rs.getString("delegated_role_id")),
                    rs.getString("delegated_role_name"),
                    UUID.fromString(rs.getString("granted_by")),
                    rs.getString("reason"),
                    rs.getTimestamp("starts_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant(),
                    revokedAt == null ? null : revokedAt.toInstant(),
                    rs.getString("revoked_reason"));
        };
    }

    private RowMapper<RoleRow> roleMapper() {
        return (rs, i) -> new RoleRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                rs.getString("name"),
                rs.getBoolean("built_in"),
                rs.getString("grants_json"),
                rs.getBoolean("mfa_required"));
    }
}
