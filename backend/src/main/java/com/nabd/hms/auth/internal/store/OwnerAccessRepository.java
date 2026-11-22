package com.nabd.hms.auth.internal.store;

import com.nabd.hms.auth.api.OwnerAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * PLT-036: the owner module's writes on login_attempts and staff, moved unchanged from OwnerRepository so that iam is the only
 * writer of both tables. See {@link OwnerAccess} for the contract; pin_hash of a shadow row stays NULL forever, so it can never
 * be signed into through the staff PIN login.
 */
@Repository
class OwnerAccessRepository implements OwnerAccess {

    private final JdbcTemplate jdbc;

    OwnerAccessRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void recordLoginAttempt(UUID ownerId, String email, String ip, boolean succeeded) {
        jdbc.update("INSERT INTO login_attempts (owner_id, email, ip_address, succeeded) VALUES (?,?,?::inet,?)",
                ownerId, email, ip, succeeded);
    }

    @Override
    public UUID findOrCreateShadowStaff(UUID ownerId, UUID clinicId, String ownerName) {
        Optional<UUID> existing = jdbc.query(
                "SELECT id FROM staff WHERE tenant_id = ? AND owner_id = ?",
                (rs, i) -> UUID.fromString(rs.getString("id")), clinicId, ownerId
        ).stream().findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }

        // LIVE-05/QA-05: provisioning's verify_invite_owner already made this owner a staff row under their real
        // email and the built-in role. Link and reuse it so /staff lists the owner once; flags/status match what a
        // new shadow row would get. Suspended rows and rows on any other role are left alone.
        Optional<UUID> provisioned = jdbc.query(
                "UPDATE staff s SET owner_id = o.id, status = 'active', email_verified = true, mobile_verified = true, " +
                        "invite_token_hash = NULL, invite_expires_at = NULL " +
                        "FROM owners o WHERE o.id = ? AND s.tenant_id = ? AND s.owner_id IS NULL AND s.email = o.email " +
                        "AND s.status IN ('active', 'invited') " +
                        "AND s.role_id IN (SELECT r.id FROM roles r WHERE r.tenant_id = s.tenant_id AND r.built_in) " +
                        "RETURNING s.id",
                (rs, i) -> UUID.fromString(rs.getString("id")), ownerId, clinicId
        ).stream().findFirst();
        if (provisioned.isPresent()) {
            return provisioned.get();
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
}
