package com.nabd.hms.platform;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NB-358 (Arogya Fabric restructuring, Phase 2): V41 is additive-only DDL with no Java model or API
 * surface yet, so there's nothing to exercise through the app's usual *ApiTest pattern. This covers
 * the two guarantees that actually matter at this stage: sane defaults for every already-provisioned
 * tenant, and that the two new facility-scoped tables (service_points, invoice_series) get the same
 * RLS isolation every other facility-scoped table has.
 */
class OrgHierarchySchemaTest extends ApiTestBase {

    @Test
    void newTenantAndDepartmentColumnsDefaultToTodaysBehaviour() {
        SeededTenant tenant = seedTenant();
        Object facilityType = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT facility_type FROM tenants WHERE id = ?", String.class, tenant.id()));
        Object billingMode = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT billing_mode FROM tenants WHERE id = ?", String.class, tenant.id()));
        assertThat(facilityType).isEqualTo("clinic");
        assertThat(billingMode).isEqualTo("per_department"); // matches this session's actual live checkout behaviour, not the doc's stated ecosystem default

        String kind = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT kind FROM departments WHERE tenant_id = ? AND is_default", String.class, tenant.id()));
        assertThat(kind).isEqualTo("clinical");
    }

    @Test
    void servicePointsAndInvoiceSeriesAreIsolatedPerTenantByRls() {
        SeededTenant tenantA = seedTenant();
        SeededTenant tenantB = seedTenant();

        UUID deptA = inTenantTx(tenantA.id(), () -> jdbc.queryForObject(
                "SELECT id FROM departments WHERE tenant_id = ? AND is_default", UUID.class, tenantA.id()));
        inTenantTx(tenantA.id(), () -> jdbc.update(
                "INSERT INTO service_points (tenant_id, department_id, kind, label) VALUES (?,?,'chair','Chair 1')",
                tenantA.id(), deptA));

        SeededOwner owner = seedOwner("legal-entity-owner@a.com");
        UUID legalEntityId = UUID.randomUUID();
        jdbc.update("INSERT INTO legal_entities (id, owner_id, name, entity_type) VALUES (?,?,?,?)",
                legalEntityId, owner.id(), "Test Clinics Pvt Ltd", "company");
        UUID taxRegId = UUID.randomUUID();
        jdbc.update("INSERT INTO tax_registrations (id, legal_entity_id, jurisdiction_code, gstin_or_vat_no, registered) VALUES (?,?,?,?,true)",
                taxRegId, legalEntityId, "IN-KL", "32AAAAA0000A1Z5");
        inTenantTx(tenantA.id(), () -> jdbc.update(
                "INSERT INTO invoice_series (tenant_id, tax_registration_id, prefix, fiscal_year) VALUES (?,?,?,?)",
                tenantA.id(), taxRegId, "KL-COC-", "2026-27"));

        // Tenant B's session (RLS keyed on app.tenant_id) must see none of tenant A's rows.
        List<UUID> spVisibleToB = inTenantTx(tenantB.id(), () -> jdbc.query(
                "SELECT id FROM service_points", (rs, i) -> UUID.fromString(rs.getString("id"))));
        List<UUID> seriesVisibleToB = inTenantTx(tenantB.id(), () -> jdbc.query(
                "SELECT id FROM invoice_series", (rs, i) -> UUID.fromString(rs.getString("id"))));
        assertThat(spVisibleToB).isEmpty();
        assertThat(seriesVisibleToB).isEmpty();

        List<String> spVisibleToA = inTenantTx(tenantA.id(), () -> jdbc.query(
                "SELECT label FROM service_points", (rs, i) -> rs.getString("label")));
        assertThat(spVisibleToA).containsExactly("Chair 1");
    }
}
