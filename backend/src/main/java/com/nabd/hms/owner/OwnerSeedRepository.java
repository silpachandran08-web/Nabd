package com.nabd.hms.owner;

import com.nabd.hms.common.ProvisioningSeeds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** PLT-036: provisioning's writes on owners, moved unchanged from platform's ProvisioningRepository. */
@Repository
class OwnerSeedRepository implements ProvisioningSeeds.Owners {

    private final JdbcTemplate jdbc;

    OwnerSeedRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID create(String name, String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO owners (id, name, email, status) VALUES (?,?,?,'active')", id, name, email);
        return id;
    }

    @Override
    public void delete(UUID ownerId) {
        jdbc.update("DELETE FROM owners WHERE id = ?", ownerId);
    }
}
