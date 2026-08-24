package com.nabd.hms.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks the Surface x Role matrix to the "SaaS Operator Roles" spec sheet (SSA-02) — a drift here is a silent access-control bug. */
class PlatformPermissionsTest {

    @Test
    void superAdminSeesEverySurface() {
        assertThat(PlatformPermissions.forRole("super_admin")).hasSize(14);
    }

    @Test
    void onlySuperAdminAndImplementationCanReachOnboarding() {
        assertThat(PlatformPermissions.forRole("implementation")).contains("onboarding_provisioning:view");
        for (String role : new String[]{"support_engineer", "billing", "sre", "commercial", "compliance_dpo"}) {
            assertThat(PlatformPermissions.forRole(role)).doesNotContain("onboarding_provisioning:view");
        }
    }

    @Test
    void supportEngineerNeverReachesBillingOrProvisioning() {
        assertThat(PlatformPermissions.forRole("support_engineer"))
                .doesNotContain("billing_revenue:view", "pricing_packaging:view", "onboarding_provisioning:view")
                .contains("command_centre:view", "clinics_fleet:view", "tenant_detail:view",
                        "support_tickets:view", "platform_health:view", "support_access:view")
                .hasSize(6);
    }

    @Test
    void commercialIsReadOnlyByConstruction() {
        // Every authority this matrix hands out is a ":view" grant — there is no ":create"/":edit"/
        // ":delete" surface anywhere yet, so commercial (or any role) cannot get write access by
        // construction, not by convention.
        assertThat(PlatformPermissions.forRole("commercial"))
                .allMatch(p -> p.endsWith(":view"))
                .containsExactlyInAnyOrder("command_centre:view", "clinics_fleet:view", "billing_revenue:view",
                        "territories:view", "aggregator_demand:view");
    }

    @Test
    void complianceDpoReachesAuditButNotBillingOrProvisioning() {
        assertThat(PlatformPermissions.forRole("compliance_dpo"))
                .contains("audit_compliance:view")
                .doesNotContain("billing_revenue:view", "pricing_packaging:view",
                        "onboarding_provisioning:view", "provisioning_jobs:view");
    }

    @Test
    void billingSeesRevenueAndPricingOnly() {
        assertThat(PlatformPermissions.forRole("billing"))
                .containsExactlyInAnyOrder("command_centre:view", "clinics_fleet:view",
                        "billing_revenue:view", "pricing_packaging:view");
    }

    @Test
    void sreSeesOperationalSurfacesNotCommercialOnes() {
        assertThat(PlatformPermissions.forRole("sre"))
                .containsExactlyInAnyOrder("command_centre:view", "provisioning_jobs:view", "territories:view",
                        "messaging_operations:view", "platform_health:view", "audit_compliance:view");
    }

    @Test
    void everyRoleSeesTheCommandCentre() {
        for (String role : new String[]{"super_admin", "implementation", "support_engineer", "billing", "sre", "commercial", "compliance_dpo"}) {
            assertThat(PlatformPermissions.forRole(role)).contains("command_centre:view");
        }
    }
}
