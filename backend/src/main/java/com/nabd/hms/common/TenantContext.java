package com.nabd.hms.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Sets app.tenant_id for the current transaction — every RLS policy in the schema (tenants
 * excepted, since resolving it is the one thing that has to happen first) checks this value.
 * Must be called before any query against an RLS-protected table; shared here rather than
 * duplicated per-repository specifically because getting it wrong is a tenant-isolation bug.
 *
 * <p>Also sets app.visible_tenant_ids (PLT-018, ADR-07): the READ set of the per-command policies.
 * It is the caller's own tenant alone, unless the legacy flag {@code nabd.legacy-brand-visibility}
 * is on AND the caller's brand is in {@code nabd.legacy-brand-visibility.allowed-brand-ids} — only
 * then are the siblings that brands.data_visibility_scope (BRAND / TENANT) names added. Writes never
 * widen (WITH CHECK uses app.tenant_id), and staff/sessions/roles ignore the GUC entirely.
 * Flag default is off in every profile; it is deliberately not in application.yml.
 */
@Component
public class TenantContext {

    private final JdbcTemplate jdbc;
    private final boolean legacyBrandVisibility;
    private final String legacyAllowedBrandIds;

    public TenantContext(JdbcTemplate jdbc,
                         @Value("${nabd.legacy-brand-visibility:false}") boolean legacyBrandVisibility,
                         @Value("${nabd.legacy-brand-visibility.allowed-brand-ids:}") List<String> allowedBrandIds) {
        this.jdbc = jdbc;
        this.legacyBrandVisibility = legacyBrandVisibility;
        // Parsed once: a malformed entry fails startup rather than silently widening or narrowing.
        this.legacyAllowedBrandIds = allowedBrandIds.stream().map(String::trim).filter(x -> !x.isEmpty())
                .map(x -> UUID.fromString(x).toString()).collect(Collectors.joining(","));
    }

    public void set(UUID tenantId) {
        // Both set_config() calls fire for their side effect regardless of how the results are
        // combined — concatenated into one column only so queryForObject(String.class) still
        // has exactly one column to read.
        jdbc.queryForObject("""
                SELECT set_config('app.tenant_id', ?, true) ||
                       set_config('app.visible_tenant_ids', ? || COALESCE((
                           SELECT ',' || string_agg(sibling.id::text, ',')
                           FROM tenants self
                           JOIN brands b ON b.id = self.brand_id
                           JOIN tenants sibling ON sibling.id <> self.id AND (
                               (b.data_visibility_scope = 'BRAND' AND sibling.brand_id = self.brand_id)
                               OR (b.data_visibility_scope = 'TENANT' AND sibling.brand_id IN
                                   (SELECT id FROM brands WHERE owner_id = b.owner_id))
                           )
                           WHERE self.id = ? AND ? AND b.id = ANY (string_to_array(?, ',')::uuid[])
                       ), ''), true)
                """, String.class, tenantId.toString(), tenantId.toString(), tenantId, legacyBrandVisibility, legacyAllowedBrandIds);
    }
}
