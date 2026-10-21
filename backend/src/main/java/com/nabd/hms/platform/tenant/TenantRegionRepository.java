package com.nabd.hms.platform.tenant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
class TenantRegionRepository {

    private final JdbcTemplate jdbc;

    TenantRegionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<String> findRegion(UUID tenantId) {
        return jdbc.query("SELECT region_code FROM tenants WHERE id = ?", (rs, i) -> rs.getString(1).trim(), tenantId)
                .stream().findFirst();
    }

    /** The trigger from V1991 refuses a different value (SQLSTATE NR001). */
    void setRegion(UUID tenantId, String regionCode) {
        jdbc.update("UPDATE tenants SET region_code = ? WHERE id = ?", regionCode, tenantId);
    }
}
