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
 *
 * Surfaces grant ":view" only. Money-moving actions on billing_revenue carry two finer authorities
 * (V2-005) so that the read-only {@code commercial} role, which shares billing_revenue:view, cannot write:
 * {@code billing_revenue:edit} (change a subscription, move it through dunning states, request a
 * discount: super_admin + billing) and {@code billing_revenue:approve} (approve or reject an above-cap
 * discount: super_admin only, per the SSA-08 note "a super-admin approval request"). The JWT is issued
 * with these at login, so an operator signed in before this change re-authenticates to pick them up.
 * The same applies to {@code tenant_detail:edit} (V3-001): tenant lifecycle transitions (suspend, offboard,
 * reactivate) are super_admin only; the other tenant_detail roles keep read access. Operators signed in before
 * this deploy must sign in again to get it.
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
            Map.entry("audit_compliance", Set.of("super_admin", "compliance_dpo")) // PLT-056 (SEC-20): sre removed
    );

    /** Finer-grained authorities beyond ":view"; each lists exactly the roles that hold it. */
    private static final Map<String, Set<String>> ACTION_ROLES = Map.of(
            "billing_revenue:edit", Set.of("super_admin", "billing"),
            "billing_revenue:approve", Set.of("super_admin"),
            "messaging_operations:override", Set.of("super_admin"), // COM-014: override a template PHI-lint false positive (audited)
            "support_access:request", Set.of("super_admin", "implementation", "support_engineer"), // PLT-056
            "safety_content:edit", Set.of("super_admin"), // CLN-004: upload / record CSO approval / activate the safety dictionary
            "kb_import:edit", Set.of("super_admin"), // CLN-019: import / activate a drug master KB feed
            "audit_compliance:values", Set.of("compliance_dpo"), // FEF-019: lets the console show the DPO-only before/after control from permissions; AuditSearchService still enforces the role
            "tenant_detail:edit", Set.of("super_admin") // V3-001: suspend / offboard / reactivate a clinic; shortcut: implementation excluded until the role spec says otherwise
    );

    static List<String> forRole(String role) {
        return java.util.stream.Stream.concat(
                        SURFACE_ROLES.entrySet().stream()
                                .filter(e -> e.getValue().contains(role))
                                .map(e -> e.getKey() + ":view"),
                        ACTION_ROLES.entrySet().stream()
                                .filter(e -> e.getValue().contains(role))
                                .map(Map.Entry::getKey))
                .sorted()
                .toList();
    }
}
