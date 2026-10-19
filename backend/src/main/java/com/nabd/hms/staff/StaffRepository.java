package com.nabd.hms.staff;

import com.nabd.hms.staff.dto.StaffRosterEntry;
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
    private final com.nabd.hms.auth.MfaPolicy mfaPolicy;

    StaffRepository(JdbcTemplate jdbc, com.nabd.hms.auth.MfaPolicy mfaPolicy) {
        this.jdbc = jdbc;
        this.mfaPolicy = mfaPolicy;
    }

    // ::citext cast is load-bearing — see AuthRepository's class comment for why a bare "= ?"
    // against a citext column silently falls back to case-sensitive text equality via JDBC.
    Optional<StaffRow> findByEmail(UUID tenantId, String email) {
        return jdbc.query(baseSelect() + "WHERE s.tenant_id = ? AND s.email = ?::citext",
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
                        "s.email_verified, s.mobile_verified, s.mfa_enabled, s.department_id, " +
                        "s.field_grants, null::timestamptz AS last_seen_at, s.created_at, null::text AS professional_registration_no, " +
                        // No tenant is set on this bootstrap path, so a roles subquery would hit roles' RLS
                        // policy with an empty app.tenant_id (uuid cast error). Not needed here: constant.
                        "(s.pin_hash IS NOT NULL) AS has_pin, (s.owner_id IS NOT NULL) AS owner_linked, " +
                        "false AS role_mfa_required, false AS role_privileged " +
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

    /** Deliberately id+name only, not baseSelect()'s full staff row — see StaffRosterEntry's doc
     * comment. Roles are free-text tenant-defined objects (no fixed "is this a doctor" column
     * anywhere), so a role literally named "Doctor" is the only signal this schema has — every
     * caller (Arrivals' check-in picker, Packages' "Allowed doctors") wants a doctor list, never
     * the whole staff directory. */
    List<StaffRosterEntry> listRoster(UUID tenantId) {
        return jdbc.query("SELECT s.id, s.name, d.name AS department_name FROM staff s JOIN roles r ON r.id = s.role_id " +
                        "LEFT JOIN departments d ON d.id = s.department_id " +
                        "WHERE s.tenant_id = ? AND s.status = 'active' AND r.name ILIKE 'doctor' ORDER BY s.name",
                (rs, i) -> new StaffRosterEntry(UUID.fromString(rs.getString("id")), rs.getString("name"),
                        rs.getString("department_name")),
                tenantId);
    }

    void insertInvite(UUID id, UUID tenantId, UUID roleId, String email, String name, String mobilePhone,
                       String scope, String inviteTokenHash, Instant inviteExpiresAt, UUID invitedBy, UUID departmentId) {
        jdbc.update(
                "INSERT INTO staff (id, tenant_id, role_id, email, name, mobile_phone, status, scope, invite_token_hash, invite_expires_at, invited_by, department_id) " +
                        "VALUES (?,?,?,?,?,?,'invited',?,?,?,?,?)",
                id, tenantId, roleId, email, name, mobilePhone, scope, inviteTokenHash, Timestamp.from(inviteExpiresAt), invitedBy, departmentId
        );
    }

    void update(UUID tenantId, UUID id, UUID roleId, String scope, List<String> fieldGrants, UUID departmentId) {
        jdbc.update("UPDATE staff SET role_id = ?, scope = ?, field_grants = ?, department_id = ? WHERE tenant_id = ? AND id = ?",
                roleId, scope, fieldGrants.toArray(new String[0]), departmentId, tenantId, id);
    }

    /** DOC-N1: separate from update() so a PATCH that does not mention the registration number cannot touch it. */
    void updateProfessionalRegistrationNo(UUID tenantId, UUID id, String registrationNo) {
        jdbc.update("UPDATE staff SET professional_registration_no = ? WHERE tenant_id = ? AND id = ?",
                registrationNo, tenantId, id);
    }

    void suspend(UUID tenantId, UUID id) {
        jdbc.update("UPDATE staff SET status = 'suspended' WHERE tenant_id = ? AND id = ?", tenantId, id);
    }

    /** PLT-004: drops the authenticator and every unused recovery code; the next sign-in must enrol again. */
    void resetMfa(UUID tenantId, UUID staffId) {
        jdbc.update("UPDATE staff SET mfa_enabled = false, mfa_secret_enc = NULL, mfa_pending_secret_enc = NULL, "
                + "mfa_pending_created_at = NULL, mfa_reset_required = true WHERE tenant_id = ? AND id = ?", tenantId, staffId);
        jdbc.update("UPDATE mfa_recovery_codes SET revoked_at = now() WHERE tenant_id = ? AND staff_id = ? "
                + "AND used_at IS NULL AND revoked_at IS NULL", tenantId, staffId);
    }

    void revokeAllSessions(UUID tenantId, UUID staffId, String reason) {
        jdbc.update("UPDATE sessions SET revoked_at = now(), revoked_reason = ? " +
                "WHERE tenant_id = ? AND staff_id = ? AND revoked_at IS NULL", reason, tenantId, staffId);
    }

    /** Accepting the emailed invite link is itself the email-verification step (NB-041). */
    void acceptInvite(UUID tenantId, UUID id, String pinHash) {
        jdbc.update("UPDATE staff SET pin_hash = ?, pin_policy_version = 2, status = 'active', email_verified = true, " +
                        "invite_token_hash = NULL, invite_expires_at = NULL " +
                        "WHERE tenant_id = ? AND id = ?",
                pinHash, tenantId, id);
    }

    private String baseSelect() {
        return "SELECT s.id, s.tenant_id, s.role_id, s.email, s.name, s.mobile_phone, s.status, s.scope, " +
                "s.email_verified, s.mobile_verified, s.mfa_enabled, s.department_id, " +
                "s.field_grants, (SELECT max(last_seen_at) FROM sessions x WHERE x.staff_id = s.id) AS last_seen_at, " +
                "s.created_at, s.professional_registration_no, " +
                // OWN-034: credential facts as booleans only — pin_hash itself never leaves SQL.
                "(s.pin_hash IS NOT NULL) AS has_pin, (s.owner_id IS NOT NULL) AS owner_linked, " +
                "COALESCE((SELECT r.mfa_required FROM roles r WHERE r.id = s.role_id), false) AS role_mfa_required, " +
                // PLT-005: same rule as MfaPolicy.privilegedRole (built-in Owner, or any approve/refundDiscount grant)
                "COALESCE((SELECT r.built_in OR EXISTS (SELECT 1 FROM jsonb_array_elements(r.grants) g " +
                "  WHERE g->>'approve' = 'true' OR g->>'refundDiscount' = 'true') FROM roles r WHERE r.id = s.role_id), false) AS role_privileged " +
                "FROM staff s ";
    }

    private RowMapper<StaffRow> staffMapper() {
        return (rs, i) -> {
            Timestamp lastSeen = rs.getTimestamp("last_seen_at");
            Array fieldGrantsArray = rs.getArray("field_grants");
            List<String> fieldGrants = fieldGrantsArray == null
                    ? List.of()
                    : Arrays.asList((String[]) fieldGrantsArray.getArray());
            String departmentId = rs.getString("department_id");
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
                    departmentId == null ? null : UUID.fromString(departmentId),
                    lastSeen == null ? null : lastSeen.toInstant(),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getString("professional_registration_no"),
                    rs.getBoolean("has_pin"),
                    rs.getBoolean("owner_linked"),
                    rs.getBoolean("role_mfa_required") || (mfaPolicy.enforced() && rs.getBoolean("role_privileged")));
        };
    }
}
