package com.nabd.hms.auth.internal.store;

import com.nabd.hms.common.PhoneNumbers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.auth.internal.store.AuthModels.Role;
import static com.nabd.hms.auth.internal.store.AuthModels.SessionRow;
import static com.nabd.hms.auth.internal.store.AuthModels.Staff;
import static com.nabd.hms.auth.internal.store.AuthModels.Tenant;

/**
 * PLT-034 (split from AuthRepository): tenant, staff and role lookups for sign-in, plus the lazy expiry of role loans and
 * break-glass grants. RLS is on staff/roles: callers set the tenant first, in the same transaction.
 *
 * Every comparison against a citext column (slug, email, mobile_phone) below casts its bound
 * parameter with ::citext. Without it, a JDBC-bound "?" is sent as plain text, and Postgres falls
 * back to text's case-SENSITIVE equality instead of citext's — the column being citext alone does
 * NOT make the comparison case-insensitive. Found via the Owner login feature (which was the first
 * code path exercised with a genuinely mixed-case value); staff/tenant lookups were already
 * exposed to the same gap, just masked in practice by callers normalizing to lowercase first.
 */
@Repository
public class StaffDirectoryRepository {

    private final JdbcTemplate jdbc;

    StaffDirectoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Tenant> findTenantBySlug(String slug) {
        return jdbc.query(
                "SELECT id, slug, status, name, region_code AS region FROM tenants WHERE slug = ?::citext",
                tenantMapper(), slug
        ).stream().findFirst();
    }

    /** For the access token's tenantName claim (ClinicNav's clinic-info card) — looked up by id
     * since minting happens well past the slug-based login step (MFA verify, OTP verify, refresh
     * all mint a fresh token from just a staff/session row, no slug in hand). */
    public Optional<Tenant> findTenantById(UUID id) {
        return jdbc.query(
                "SELECT id, slug, status, name, region_code AS region FROM tenants WHERE id = ?",
                tenantMapper(), id
        ).stream().findFirst();
    }

    private static final String STAFF_COLUMNS =
            "id, tenant_id, role_id, email, name, mobile_phone, pin_hash, status, scope, " +
                    "email_verified, mobile_verified, mfa_enabled, mfa_secret_enc, mfa_pending_secret_enc, mfa_reset_required ";

    public Optional<Staff> findStaffByEmail(UUID tenantId, String email) {
        return jdbc.query("SELECT " + STAFF_COLUMNS + "FROM staff WHERE tenant_id = ? AND email = ?::citext",
                staffMapper(), tenantId, email
        ).stream().findFirst();
    }

    /**
     * Matches however the number was typed — "98765 43210", "+91 98765 43210", "0091…" — against
     * however it was stored, by comparing digits with the country code filled in from the clinic's
     * region. An exact match on the raw text meant any other format silently found nobody (and,
     * by design, the OTP endpoint still answers "sent"), so no code was ever generated.
     */
    public Optional<Staff> findStaffByMobile(UUID tenantId, String region, String mobilePhone) {
        return jdbc.query("SELECT " + STAFF_COLUMNS + "FROM staff WHERE tenant_id = ? AND mobile_phone IS NOT NULL " +
                        "AND regexp_replace(mobile_phone, '\\D', '', 'g') = ?",
                staffMapper(), tenantId, PhoneNumbers.digits(mobilePhone, region)
        ).stream().findFirst();
    }

    public Optional<Staff> findStaffById(UUID staffId) {
        return jdbc.query("SELECT " + STAFF_COLUMNS + "FROM staff WHERE id = ?",
                staffMapper(), staffId
        ).stream().findFirst();
    }

    /** FIN-018: active staff of the bound tenant on the built-in Owner role (RefundService.OWNER_ROLE). */
    public List<Staff> findActiveOwners() {
        return jdbc.query("SELECT " + STAFF_COLUMNS + "FROM staff WHERE tenant_id = app_tenant_id() AND status = 'active' "
                + "AND role_id IN (SELECT id FROM roles WHERE built_in AND name = 'Owner') ORDER BY id", staffMapper());
    }


    // ── break-glass (NB-048) ─────────────────────────────────────────────

    /** Same "detect and close on next read" shape as delegation expiry — see AuthService.mintTokenPair. */
    public List<UUID> expireStaleBreakGlass(UUID staffId) {
        return jdbc.query(
                "UPDATE break_glass_grants SET deactivated_at = expires_at, deactivated_reason = 'expired' " +
                        "WHERE staff_id = ? AND deactivated_at IS NULL AND expires_at <= now() RETURNING id",
                (rs, i) -> UUID.fromString(rs.getString("id")), staffId);
    }

    public Optional<Role> findRole(UUID roleId) {
        return jdbc.query(
                "SELECT id, tenant_id, name, grants::text AS grants_json, mfa_required, built_in FROM roles WHERE id = ?",
                roleMapper(), roleId
        ).stream().findFirst();
    }

    /** NB-048: break-glass elevates to whatever the tenant's built-in Owner role currently grants. */
    public Optional<Role> findBuiltInOwnerRole(UUID tenantId) {
        return jdbc.query(
                "SELECT id, tenant_id, name, grants::text AS grants_json, mfa_required, built_in FROM roles WHERE tenant_id = ? AND built_in = true",
                roleMapper(), tenantId
        ).stream().findFirst();
    }

    /**
     * NB-057: roles on loan to this staff member right now, via role_delegations — a narrow direct
     * query into the staff/roles module's table rather than injecting RoleRepository across package
     * boundaries (same convention as CheckoutRepository reading pharmacy/procedure_orders directly).
     */
    public List<Role> findActiveDelegatedRoles(UUID staffId) {
        return jdbc.query(
                "SELECT r.id, r.tenant_id, r.name, r.grants::text AS grants_json, r.mfa_required, r.built_in " +
                        "FROM role_delegations d JOIN roles r ON r.id = d.delegated_role_id " +
                        "WHERE d.staff_id = ? AND d.revoked_at IS NULL AND d.starts_at <= now() AND d.expires_at > now()",
                roleMapper(), staffId);
    }

    /**
     * Closes out every delegation for this staff member that has passed its expires_at but was
     * never revoked, returning the ids just closed — the caller audits exactly those. No scheduler
     * exists to do this the instant a delegation lapses, so the next login/refresh (at most one
     * access-token TTL later) is what notices and logs it — same shape as the lockout counter.
     */
    public List<UUID> expireStaleDelegations(UUID staffId) {
        return jdbc.query(
                "UPDATE role_delegations SET revoked_at = expires_at, revoked_reason = 'expired' " +
                        "WHERE staff_id = ? AND revoked_at IS NULL AND expires_at <= now() RETURNING id",
                (rs, i) -> UUID.fromString(rs.getString("id")), staffId);
    }

    /** PLT-005: an owner's shadow staff row (staff.owner_id set) is reached only through the owner account's own MFA. */
    public boolean isOwnerLinked(UUID staffId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT owner_id IS NOT NULL FROM staff WHERE id = ?", Boolean.class, staffId));
    }


    private RowMapper<Tenant> tenantMapper() {
        return (rs, i) -> new Tenant(
                UUID.fromString(rs.getString("id")), rs.getString("slug"), rs.getString("status"),
                rs.getString("name"), rs.getString("region"));
    }

    private RowMapper<Staff> staffMapper() {
        return (rs, i) -> new Staff(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                UUID.fromString(rs.getString("role_id")),
                rs.getString("email"),
                rs.getString("name"),
                rs.getString("mobile_phone"),
                rs.getString("pin_hash"),
                rs.getString("status"),
                rs.getString("scope"),
                rs.getBoolean("email_verified"),
                rs.getBoolean("mobile_verified"),
                rs.getBoolean("mfa_enabled"),
                rs.getBytes("mfa_secret_enc"),
                rs.getBytes("mfa_pending_secret_enc"),
                rs.getBoolean("mfa_reset_required"));
    }

    private RowMapper<Role> roleMapper() {
        return (rs, i) -> new Role(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                rs.getString("name"),
                rs.getString("grants_json"),
                rs.getBoolean("mfa_required"),
                rs.getBoolean("built_in"));
    }
}
