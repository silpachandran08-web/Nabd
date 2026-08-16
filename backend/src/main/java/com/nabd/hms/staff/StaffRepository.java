package com.nabd.hms.staff;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.staff.StaffRoleModels.StaffRow;

@Repository
class StaffRepository {

    private final JdbcTemplate jdbc;

    StaffRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<StaffRow> findByEmail(UUID tenantId, String email) {
        return jdbc.query(baseSelect() + "WHERE s.tenant_id = ? AND s.email = ?",
                staffMapper(), tenantId, email).stream().findFirst();
    }

    Optional<StaffRow> findById(UUID tenantId, UUID id) {
        return jdbc.query(baseSelect() + "WHERE s.tenant_id = ? AND s.id = ?",
                staffMapper(), tenantId, id).stream().findFirst();
    }

    /** Bootstrap lookup for invite-accept — see find_staff_by_invite_token_hash in V2, same pattern as refresh tokens. */
    Optional<StaffRow> findByInviteTokenHash(String tokenHash) {
        return jdbc.query(
                "SELECT s.id, s.tenant_id, s.role_id, s.email, s.name, s.mobile_phone, s.status, s.scope, " +
                        "s.email_verified, s.mobile_verified, s.mfa_enabled, " +
                        "s.field_grants, null::timestamptz AS last_seen_at, s.created_at " +
                        "FROM find_staff_by_invite_token_hash(?) s " +
                        "WHERE s.invite_expires_at > now()",
                staffMapper(), tokenHash
        ).stream().findFirst();
    }

    List<StaffRow> listPage(UUID tenantId, int limit, Instant afterCreatedAt, UUID afterId) {
        if (afterCreatedAt == null) {
            return jdbc.query(baseSelect() + "WHERE s.tenant_id = ? ORDER BY s.created_at, s.id LIMIT ?",
                    staffMapper(), tenantId, limit);
        }
        return jdbc.query(baseSelect() +
                        "WHERE s.tenant_id = ? AND (s.created_at, s.id) > (?, ?) ORDER BY s.created_at, s.id LIMIT ?",
                staffMapper(), tenantId, Timestamp.from(afterCreatedAt), afterId, limit);
    }

    void insertInvite(UUID id, UUID tenantId, UUID roleId, String email, String name, String mobilePhone,
                       String scope, String inviteTokenHash, Instant inviteExpiresAt, UUID invitedBy) {
        jdbc.update(
                "INSERT INTO staff (id, tenant_id, role_id, email, name, mobile_phone, status, scope, invite_token_hash, invite_expires_at, invited_by) " +
                        "VALUES (?,?,?,?,?,?,'invited',?,?,?,?)",
                id, tenantId, roleId, email, name, mobilePhone, scope, inviteTokenHash, Timestamp.from(inviteExpiresAt), invitedBy
        );
    }

    void update(UUID tenantId, UUID id, UUID roleId, String scope, List<String> fieldGrants) {
        jdbc.update("UPDATE staff SET role_id = ?, scope = ?, field_grants = ? WHERE tenant_id = ? AND id = ?",
                roleId, scope, fieldGrants.toArray(new String[0]), tenantId, id);
    }

    void suspend(UUID tenantId, UUID id) {
        jdbc.update("UPDATE staff SET status = 'suspended' WHERE tenant_id = ? AND id = ?", tenantId, id);
    }

    void revokeAllSessions(UUID tenantId, UUID staffId, String reason) {
        jdbc.update("UPDATE sessions SET revoked_at = now(), revoked_reason = ? " +
                "WHERE tenant_id = ? AND staff_id = ? AND revoked_at IS NULL", reason, tenantId, staffId);
    }

    /** Accepting the emailed invite link is itself the email-verification step (NB-041). */
    void acceptInvite(UUID tenantId, UUID id, String pinHash) {
        jdbc.update("UPDATE staff SET pin_hash = ?, status = 'active', email_verified = true, " +
                        "invite_token_hash = NULL, invite_expires_at = NULL " +
                        "WHERE tenant_id = ? AND id = ?",
                pinHash, tenantId, id);
    }

    private String baseSelect() {
        return "SELECT s.id, s.tenant_id, s.role_id, s.email, s.name, s.mobile_phone, s.status, s.scope, " +
                "s.email_verified, s.mobile_verified, s.mfa_enabled, " +
                "s.field_grants, (SELECT max(last_seen_at) FROM sessions x WHERE x.staff_id = s.id) AS last_seen_at, " +
                "s.created_at FROM staff s ";
    }

    private RowMapper<StaffRow> staffMapper() {
        return (rs, i) -> {
            Timestamp lastSeen = rs.getTimestamp("last_seen_at");
            Array fieldGrantsArray = rs.getArray("field_grants");
            List<String> fieldGrants = fieldGrantsArray == null
                    ? List.of()
                    : Arrays.asList((String[]) fieldGrantsArray.getArray());
            return new StaffRow(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("tenant_id")),
                    UUID.fromString(rs.getString("role_id")),
                    rs.getString("email"),
                    rs.getString("name"),
                    rs.getString("mobile_phone"),
                    rs.getString("status"),
                    rs.getString("scope"),
                    rs.getBoolean("email_verified"),
                    rs.getBoolean("mobile_verified"),
                    rs.getBoolean("mfa_enabled"),
                    fieldGrants,
                    lastSeen == null ? null : lastSeen.toInstant(),
                    rs.getTimestamp("created_at").toInstant());
        };
    }
}
