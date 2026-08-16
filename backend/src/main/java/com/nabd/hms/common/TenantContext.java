package com.nabd.hms.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Sets app.tenant_id for the current transaction — every RLS policy in the schema (tenants
 * excepted, since resolving it is the one thing that has to happen first) checks this value.
 * Must be called before any query against an RLS-protected table; shared here rather than
 * duplicated per-repository specifically because getting it wrong is a tenant-isolation bug.
 */
@Component
public class TenantContext {

    private final JdbcTemplate jdbc;

    public TenantContext(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void set(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }
}
