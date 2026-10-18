package com.nabd.hms.platform;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PLT-018: the legacy flag ON and the brand on the allow-list (test only): reads widen as the brand's
 * scope says, writes into siblings still fail, and staff/sessions/roles never widen. A brand that is
 * NOT on the allow-list stays contained even with the flag on.
 */
@TestPropertySource(properties = {
        "nabd.legacy-brand-visibility=true",
        "nabd.legacy-brand-visibility.allowed-brand-ids=00000000-0000-0000-0000-0000000018aa"})
class LegacyBrandVisibilityTest extends ApiTestBase {

    private static final UUID ALLOWED = UUID.fromString("00000000-0000-0000-0000-0000000018aa");

    private void brand(SeededOwner owner, UUID id, String name) {
        jdbc.update("INSERT INTO brands (id, owner_id, name, status, data_visibility_scope) VALUES (?,?,?,'active','BRAND')",
                id, owner.id(), name);
    }

    private SeededTenant clinic(UUID brandId) {
        SeededTenant t = seedTenant();
        jdbc.update("UPDATE tenants SET brand_id = ? WHERE id = ?", brandId, t.id());
        seedFullAccessRole(t.id());
        return t;
    }

    private void patient(UUID sessionTenant, UUID rowTenant, String name) {
        inTenantTx(sessionTenant, () -> jdbc.update(
                "INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,?,'+919000000000','1990-01-01','other')",
                rowTenant, name));
    }

    private List<String> names(UUID tenantId, String table, String col) {
        return inTenantTx(tenantId, () -> jdbc.query("SELECT " + col + " FROM " + table, (rs, i) -> rs.getString(1)));
    }

    @Test
    void allowListedBrandReadsWidenButWritesAndStaffSessionsRolesDoNot() {
        SeededOwner owner = seedOwner("legacy-on@a.com");
        brand(owner, ALLOWED, "Allowed");
        SeededTenant a = clinic(ALLOWED);
        SeededTenant b = clinic(ALLOWED);
        patient(a.id(), a.id(), "Patient A");
        patient(b.id(), b.id(), "Patient B");

        assertThat(names(a.id(), "patients", "name")).containsExactlyInAnyOrder("Patient A", "Patient B");

        assertThatThrownBy(() -> patient(a.id(), b.id(), "Smuggled")).rootCause().hasMessageContaining("row-level security");
        assertThatThrownBy(() -> inTenantTx(a.id(), () -> jdbc.update("UPDATE patients SET name = 'Hacked' WHERE name = 'Patient B'")))
                .rootCause().hasMessageContaining("row-level security");
        assertThat(inTenantTx(a.id(), () -> jdbc.update("DELETE FROM patients WHERE name = 'Patient B'"))).isZero();

        // staff / roles (and so sessions, same single-tenant policy form): the flag widens nothing
        assertThat(names(a.id(), "roles", "tenant_id::text")).containsOnly(a.id().toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_policies WHERE tablename IN ('staff','sessions','roles') AND qual LIKE '%visible_tenant_ids%'",
                Integer.class)).isZero();
    }

    @Test
    void aBrandOutsideTheAllowListStaysContainedEvenWithTheFlagOn() {
        SeededOwner owner = seedOwner("legacy-off-brand@a.com");
        UUID other = UUID.randomUUID();
        brand(owner, other, "Not allowed");
        SeededTenant a = clinic(other);
        SeededTenant b = clinic(other);
        patient(a.id(), a.id(), "Patient A");
        patient(b.id(), b.id(), "Patient B");

        assertThat(names(a.id(), "patients", "name")).containsExactly("Patient A");
    }
}
