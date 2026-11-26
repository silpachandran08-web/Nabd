package com.nabd.control;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Writes to tenant_directory. Every write is an idempotent upsert, so a redelivered event changes nothing. */
@Component
public class TenantDirectory {

    private final JdbcTemplate jdbc;

    TenantDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Applied only when the event is newer than the last status applied for this tenant (data.version is monotonic per tenant). */
    void status(UUID tenantId, String status, long version) {
        jdbc.update("INSERT INTO tenant_directory (tenant_id, status, status_version) VALUES (?, ?, ?) "
                + "ON CONFLICT (tenant_id) DO UPDATE SET status = excluded.status, status_version = excluded.status_version, updated_at = now() "
                + "WHERE tenant_directory.status_version < excluded.status_version", tenantId, status, version);
    }

    void region(UUID tenantId, String regionCode, String cell) {
        jdbc.update("INSERT INTO tenant_directory (tenant_id, region_code, cell) VALUES (?, ?, ?) "
                + "ON CONFLICT (tenant_id) DO UPDATE SET region_code = excluded.region_code, cell = excluded.cell, updated_at = now()",
                tenantId, regionCode, cell);
    }

    void brand(UUID tenantId, UUID brandId, UUID ownerId) {
        jdbc.update("INSERT INTO tenant_directory (tenant_id, brand_id, owner_id) VALUES (?, ?, ?) "
                + "ON CONFLICT (tenant_id) DO UPDATE SET brand_id = excluded.brand_id, owner_id = excluded.owner_id, updated_at = now()",
                tenantId, brandId, ownerId);
    }
}
