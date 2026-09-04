package com.nabd.hms.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Sets app.tenant_id for the current transaction — every RLS policy in the schema (tenants
 * excepted, since resolving it is the one thing that has to happen first) checks this value.
 * Must be called before any query against an RLS-protected table; shared here rather than
 * duplicated per-repository specifically because getting it wrong is a tenant-isolation bug.
 *
 * <p>Also sets app.accessible_tenant_ids (NB-359) — every sibling facility this tenant's brand
 * visibility scope (brands.data_visibility_scope) makes visible, computed fresh from the brand's
 * *current* scope on every call so a scope change takes effect on the caller's very next request.
 * FACILITY scope (the default) and a tenant with no brand both resolve to no siblings at all, so
 * this is a no-op for every tenant today — see V6's tenant_isolation policies, already wired to
 * read this GUC, and V42 for the scope column itself.
 */
@Component
public class TenantContext {

    private final JdbcTemplate jdbc;

    public TenantContext(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void set(UUID tenantId) {
        // Both set_config() calls fire for their side effect regardless of how the results are
        // combined — concatenated into one column here only so queryForObject(String.class) still
        // has exactly one column to read, same shape as before this method did two things.
        jdbc.queryForObject("""
                SELECT set_config('app.tenant_id', ?, true) ||
                       set_config('app.accessible_tenant_ids', COALESCE((
                           SELECT string_agg(sibling.id::text, ',')
                           FROM tenants self
                           JOIN brands b ON b.id = self.brand_id
                           JOIN tenants sibling ON sibling.id <> self.id AND (
                               (b.data_visibility_scope = 'BRAND' AND sibling.brand_id = self.brand_id)
                               OR (b.data_visibility_scope = 'TENANT' AND sibling.brand_id IN
                                   (SELECT id FROM brands WHERE owner_id = b.owner_id))
                           )
                           WHERE self.id = ?
                       ), ''), true)
                """, String.class, tenantId.toString(), tenantId);
    }
}
