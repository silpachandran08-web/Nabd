package com.nabd.hms.platform;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Surface x Role matrix from the "SaaS Operator Roles" spec (SSA-02, E22 Platform Console) — seven
 * fixed platform roles, not user-configurable grants like clinic RBAC. A role missing a surface gets
 * no nav entry and no create button for it anywhere; onboarding_provisioning being super_admin +
 * implementation only is what makes tenant creation exclusive to those two roles (NB-258's future
 * create-tenant endpoint gates on that authority, nothing further needed here).
 */
final class PlatformPermissions {

    private PlatformPermissions() {
    }

    private static final Map<String, Set<String>> SURFACE_ROLES = Map.ofEntries(
            Map.entry("command_centre", Set.of("super_admin", "implementation", "support_engineer", "billing", "sre", "commercial", "compliance_dpo")),
            Map.entry("clinics_fleet", Set.of("super_admin", "implementation", "support_engineer", "billing", "commercial", "compliance_dpo")),
            Map.entry("tenant_detail", Set.of("super_admin", "implementation", "support_engineer", "compliance_dpo")),
            Map.entry("onboarding_provisioning", Set.of("super_admin", "implementation")),
            Map.entry("provisioning_jobs", Set.of("super_admin", "implementation", "sre")),
            Map.entry("billing_revenue", Set.of("super_admin", "billing", "commercial")),
            Map.entry("support_tickets", Set.of("super_admin", "implementation", "support_engineer")),
            Map.entry("pricing_packaging", Set.of("super_admin", "billing")),
            Map.entry("territories", Set.of("super_admin", "sre", "commercial", "compliance_dpo")),
            Map.entry("messaging_operations", Set.of("super_admin", "implementation", "sre")),
            Map.entry("platform_health", Set.of("super_admin", "support_engineer", "sre")),
            Map.entry("aggregator_demand", Set.of("super_admin", "commercial")),
            Map.entry("support_access", Set.of("super_admin", "implementation", "support_engineer")),
            Map.entry("audit_compliance", Set.of("super_admin", "sre", "compliance_dpo"))
    );

    static List<String> forRole(String role) {
        return SURFACE_ROLES.entrySet().stream()
                .filter(e -> e.getValue().contains(role))
                .map(e -> e.getKey() + ":view")
                .sorted()
                .toList();
    }
}
