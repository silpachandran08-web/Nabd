package com.nabd.hms.platform;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NB-359 (Arogya Fabric restructuring, Phase 3): TenantContext.set() now also computes
 * app.accessible_tenant_ids from the active tenant's brand visibility scope — the GUC every
 * tenant_isolation policy V6 patched already reads, but nothing ever populated until now. Default
 * (FACILITY) scope must stay byte-identical to today; BRAND/TENANT scope must actually widen
 * visibility, and only as far as the scope says. Uses `patients`, one of the tables V6 patched.
 */
class CrossFacilityVisibilityTest extends ApiTestBase {

    private void setScope(UUID brandId, String scope) {
        jdbc.update("UPDATE brands SET data_visibility_scope = ? WHERE id = ?", scope, brandId);
    }

    private void insertPatient(UUID tenantId, String name) {
        inTenantTx(tenantId, () -> jdbc.update(
                "INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,?,'+919000000000','1990-01-01','other')",
                tenantId, name));
    }

    private List<String> patientNamesVisibleFrom(UUID tenantId) {
        return inTenantTx(tenantId, () -> jdbc.query("SELECT name FROM patients", (rs, i) -> rs.getString("name")));
    }

    @Test
    void defaultFacilityScopeStaysIsolatedEvenWithinTheSameBrand() {
        SeededOwner owner = seedOwner("scope-default@a.com");
        SeededBrand brand = seedBrand(owner, "Default Scope Brand");
        SeededTenant tenantA = seedClinicInBrand(brand);
        SeededTenant tenantB = seedClinicInBrand(brand);
        insertPatient(tenantA.id(), "Patient A");
        insertPatient(tenantB.id(), "Patient B");

        assertThat(patientNamesVisibleFrom(tenantA.id())).containsExactly("Patient A");
        assertThat(patientNamesVisibleFrom(tenantB.id())).containsExactly("Patient B");
    }

    @Test
    void brandScopeSeesEveryFacilityUnderTheSameBrand() {
        SeededOwner owner = seedOwner("scope-brand@a.com");
        SeededBrand brand = seedBrand(owner, "Brand Scope Brand");
        setScope(brand.id(), "BRAND");
        SeededTenant tenantA = seedClinicInBrand(brand);
        SeededTenant tenantB = seedClinicInBrand(brand);
        insertPatient(tenantA.id(), "Patient A");
        insertPatient(tenantB.id(), "Patient B");

        assertThat(patientNamesVisibleFrom(tenantA.id())).containsExactlyInAnyOrder("Patient A", "Patient B");
        assertThat(patientNamesVisibleFrom(tenantB.id())).containsExactlyInAnyOrder("Patient A", "Patient B");
    }

    @Test
    void brandScopeDoesNotLeakAcrossAnUnrelatedBrand() {
        SeededOwner owner = seedOwner("scope-brand2@a.com");
        SeededBrand brandX = seedBrand(owner, "Brand X");
        SeededBrand brandY = seedBrand(owner, "Brand Y");
        setScope(brandX.id(), "BRAND");
        setScope(brandY.id(), "BRAND");
        SeededTenant tenantA = seedClinicInBrand(brandX);
        SeededTenant tenantC = seedClinicInBrand(brandY);
        insertPatient(tenantA.id(), "Patient A");
        insertPatient(tenantC.id(), "Patient C");

        assertThat(patientNamesVisibleFrom(tenantA.id())).containsExactly("Patient A");
    }

    @Test
    void tenantScopeSeesEveryFacilityAcrossEveryBrandOfTheSameOwner() {
        SeededOwner owner = seedOwner("scope-tenant@a.com");
        SeededBrand brandX = seedBrand(owner, "Tenant Scope Brand X");
        SeededBrand brandY = seedBrand(owner, "Tenant Scope Brand Y");
        setScope(brandX.id(), "TENANT");
        SeededTenant tenantA = seedClinicInBrand(brandX);
        SeededTenant tenantC = seedClinicInBrand(brandY);
        insertPatient(tenantA.id(), "Patient A");
        insertPatient(tenantC.id(), "Patient C");

        assertThat(patientNamesVisibleFrom(tenantA.id())).containsExactlyInAnyOrder("Patient A", "Patient C");
    }

    @Test
    void changingScopeTakesEffectOnTheVeryNextRequestWithNoOtherChange() {
        SeededOwner owner = seedOwner("scope-live@a.com");
        SeededBrand brand = seedBrand(owner, "Live Scope Brand");
        SeededTenant tenantA = seedClinicInBrand(brand);
        SeededTenant tenantB = seedClinicInBrand(brand);
        insertPatient(tenantA.id(), "Patient A");
        insertPatient(tenantB.id(), "Patient B");

        assertThat(patientNamesVisibleFrom(tenantA.id())).containsExactly("Patient A");

        setScope(brand.id(), "BRAND");
        assertThat(patientNamesVisibleFrom(tenantA.id())).containsExactlyInAnyOrder("Patient A", "Patient B");
    }
}
