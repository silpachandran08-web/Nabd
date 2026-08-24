package com.nabd.hms.owner;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.owner.OwnerModels.BrandWorkspace;
import static com.nabd.hms.owner.OwnerModels.ClinicSummary;
import static com.nabd.hms.owner.OwnerModels.Owner;

/**
 * owners/brands carry no RLS (see V6 migration comment — nothing above the per-row-scoped tables
 * needs row security; access is an explicit owner_id/brand_id filter here instead). Any query that
 * touches staff/roles (both RLS-protected) requires TenantContext.set(clinicId) first, same rule as
 * every other repository in this codebase.
 */
@Repository
class OwnerRepository {

    private final JdbcTemplate jdbc;

    OwnerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * ::citext cast is load-bearing, not decoration: a bare "email = ?" binds the JDBC parameter as
     * plain text, and Postgres then uses text's case-SENSITIVE equality instead of citext's — the
     * column being citext alone doesn't make a bound-parameter comparison case-insensitive. Confirmed
     * empirically (a mixed-case seeded email failed to match its own lowercased lookup without this).
     */
    Optional<Owner> findByEmail(String email) {
        return jdbc.query("SELECT id, name, email, pin_hash, status FROM owners WHERE email = ?::citext",
                ownerMapper(), email
        ).stream().findFirst();
    }

    Optional<Owner> findById(UUID id) {
        return jdbc.query("SELECT id, name, email, pin_hash, status FROM owners WHERE id = ?",
                ownerMapper(), id
        ).stream().findFirst();
    }

    void recordLoginAttempt(UUID ownerId, String email, String ip, boolean succeeded) {
        jdbc.update("INSERT INTO login_attempts (owner_id, email, ip_address, succeeded) VALUES (?,?,?::inet,?)",
                ownerId, email, ip, succeeded);
    }

    /** Same "failures since last success" window as AuthRepository's staff equivalent. */
    int countFailedAttemptsSinceLastSuccess(UUID ownerId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM login_attempts " +
                        "WHERE owner_id = ? AND succeeded = false " +
                        "AND attempted_at > COALESCE(" +
                        "  (SELECT max(attempted_at) FROM login_attempts WHERE owner_id = ? AND succeeded = true)," +
                        "  now() - interval '1 day')",
                Integer.class, ownerId, ownerId
        );
        return count == null ? 0 : count;
    }

    Optional<Instant> lastFailedAttemptAt(UUID ownerId) {
        return jdbc.query(
                "SELECT max(attempted_at) AS t FROM login_attempts WHERE owner_id = ? AND succeeded = false",
                (rs, i) -> rs.getTimestamp("t"), ownerId
        ).stream().filter(java.util.Objects::nonNull).findFirst().map(Timestamp::toInstant);
    }

    int countAttemptsFromIpSince(String ip, Instant since) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM login_attempts WHERE ip_address = ?::inet AND attempted_at > ?",
                Integer.class, ip, Timestamp.from(since)
        );
        return count == null ? 0 : count;
    }

    /** Every brand this owner has, each with its clinics (a brand with no clinics yet still appears, empty). */
    List<BrandWorkspace> listWorkspaces(UUID ownerId) {
        Map<UUID, List<ClinicSummary>> clinicsByBrand = new LinkedHashMap<>();
        Map<UUID, String[]> brandMeta = new LinkedHashMap<>(); // brandId -> [name, status]

        jdbc.query(
                "SELECT b.id AS brand_id, b.name AS brand_name, b.status AS brand_status, " +
                        "       t.id AS clinic_id, t.name AS clinic_name, t.slug AS clinic_slug, t.region, t.status AS clinic_status " +
                        "FROM brands b LEFT JOIN tenants t ON t.brand_id = b.id " +
                        "WHERE b.owner_id = ? ORDER BY b.name, t.name",
                rs -> {
                    UUID brandId = UUID.fromString(rs.getString("brand_id"));
                    brandMeta.putIfAbsent(brandId, new String[]{rs.getString("brand_name"), rs.getString("brand_status")});
                    List<ClinicSummary> clinics = clinicsByBrand.computeIfAbsent(brandId, k -> new ArrayList<>());
                    String clinicId = rs.getString("clinic_id");
                    if (clinicId != null) {
                        clinics.add(new ClinicSummary(UUID.fromString(clinicId), rs.getString("clinic_name"),
                                rs.getString("clinic_slug"), rs.getString("region"), rs.getString("clinic_status")));
                    }
                },
                ownerId
        );

        List<BrandWorkspace> result = new ArrayList<>();
        for (var entry : brandMeta.entrySet()) {
            String[] meta = entry.getValue();
            result.add(new BrandWorkspace(entry.getKey(), meta[0], meta[1], clinicsByBrand.get(entry.getKey())));
        }
        return result;
    }

    /** Data-scoped authorization: does this clinic belong to one of this owner's brands? */
    boolean ownsClinic(UUID ownerId, UUID clinicId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM tenants t JOIN brands b ON t.brand_id = b.id " +
                        "WHERE t.id = ? AND b.owner_id = ?",
                Integer.class, clinicId, ownerId
        );
        return count != null && count > 0;
    }

    /**
     * One staff row per (owner, clinic), created lazily the first time an Owner enters that clinic's
     * workspace. Reuses the clinic's existing built-in (full-access) role rather than every controller
     * needing a second notion of "caller identity." pin_hash stays NULL forever — AuthService.login()
     * treats a null pinHash as an automatic no-match, so this row can never be logged into via the
     * normal staff PIN login, only reached via the owner workspace-select path. Caller must have
     * already called TenantContext.set(clinicId) in this transaction (staff/roles are RLS-protected).
     */
    UUID findOrCreateShadowStaff(UUID ownerId, UUID clinicId, String ownerName) {
        Optional<UUID> existing = jdbc.query(
                "SELECT id FROM staff WHERE tenant_id = ? AND owner_id = ?",
                (rs, i) -> UUID.fromString(rs.getString("id")), clinicId, ownerId
        ).stream().findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID roleId = jdbc.query(
                "SELECT id FROM roles WHERE tenant_id = ? AND built_in = true ORDER BY created_at LIMIT 1",
                (rs, i) -> UUID.fromString(rs.getString("id")), clinicId
        ).stream().findFirst().orElseThrow(() ->
                new IllegalStateException("clinic " + clinicId + " has no built-in role to grant its owner"));

        UUID staffId = UUID.randomUUID();
        String syntheticEmail = ownerId + "@owner.internal.nabd";
        jdbc.update(
                "INSERT INTO staff (id, tenant_id, role_id, email, name, status, email_verified, mobile_verified, owner_id) " +
                        "VALUES (?,?,?,?,?,'active',true,true,?)",
                staffId, clinicId, roleId, syntheticEmail, ownerName, ownerId
        );
        return staffId;
    }

    private RowMapper<Owner> ownerMapper() {
        return (rs, i) -> new Owner(
                UUID.fromString(rs.getString("id")),
                rs.getString("name"),
                rs.getString("email"),
                rs.getString("pin_hash"),
                rs.getString("status"));
    }
}
