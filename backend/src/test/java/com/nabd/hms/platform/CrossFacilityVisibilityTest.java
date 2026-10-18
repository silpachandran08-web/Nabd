package com.nabd.hms.platform;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PLT-018 (ADR-07, CONS M1): brand visibility is CONTAINED. With the legacy flag off (the default in
 * every profile) FACILITY / BRAND / TENANT scope all read only the caller's own facility, and no
 * statement can write a row carrying a sibling's tenant_id. Staff, sessions and roles never widen.
 * The flag-on + allow-list behaviour is in LegacyBrandVisibilityTest. Uses `patients` (V6 table).
 */
class CrossFacilityVisibilityTest extends ApiTestBase {

    static final String[] WIDENED = {"roles", "staff", "sessions", "patients", "doctor_working_hours", "doctor_leave",
            "appointments", "queue_entries", "consents", "patient_merges", "clinical_notes", "clinic_setup_progress",
            "charge_catalogue", "clinic_policies", "clinic_holidays", "staff_shifts", "attendance_records",
            "licence_registry", "data_import_jobs", "data_export_jobs"};

    private void setScope(UUID brandId, String scope) {
        jdbc.update("UPDATE brands SET data_visibility_scope = ? WHERE id = ?", scope, brandId);
    }

    private void insertPatient(UUID sessionTenant, UUID rowTenant, String name) {
        inTenantTx(sessionTenant, () -> jdbc.update(
                "INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,?,'+919000000000','1990-01-01','other')",
                rowTenant, name));
    }

    private List<String> patientNamesVisibleFrom(UUID tenantId) {
        return inTenantTx(tenantId, () -> jdbc.query("SELECT name FROM patients", (rs, i) -> rs.getString("name")));
    }

    /** Two clinics under one brand/owner, brand X also has a sibling brand Y clinic under the same owner. */
    private record Group(SeededTenant a, SeededTenant b, SeededTenant otherBrand) {}

    private Group group(String email, String scope) {
        SeededOwner owner = seedOwner(email);
        SeededBrand x = seedBrand(owner, "X " + email);
        SeededBrand y = seedBrand(owner, "Y " + email);
        setScope(x.id(), scope);
        Group g = new Group(seedClinicInBrand(x), seedClinicInBrand(x), seedClinicInBrand(y));
        insertPatient(g.a().id(), g.a().id(), "Patient A");
        insertPatient(g.b().id(), g.b().id(), "Patient B");
        insertPatient(g.otherBrand().id(), g.otherBrand().id(), "Patient C");
        return g;
    }

    @ParameterizedTest
    @ValueSource(strings = {"FACILITY", "BRAND", "TENANT"})
    void flagOffEveryScopeReadsOnlyTheCallersOwnFacility(String scope) {
        Group g = group("read-" + scope.toLowerCase() + "@a.com", scope);

        assertThat(patientNamesVisibleFrom(g.a().id())).containsExactly("Patient A");
        assertThat(patientNamesVisibleFrom(g.b().id())).containsExactly("Patient B");
        assertThat(patientNamesVisibleFrom(g.otherBrand().id())).containsExactly("Patient C");
    }

    @Test
    void visibleTenantIdsIsTheSingleCallerTenantEvenForBrandScope() {
        Group g = group("guc@a.com", "BRAND");
        String guc = inTenantTx(g.a().id(), () -> jdbc.queryForObject(
                "SELECT current_setting('app.visible_tenant_ids')", String.class));
        assertThat(guc).isEqualTo(g.a().id().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FACILITY", "BRAND", "TENANT"})
    void insertingARowForAnotherTenantFailsTheWithCheck(String scope) {
        Group g = group("ins-" + scope.toLowerCase() + "@a.com", scope);

        assertThatThrownBy(() -> insertPatient(g.a().id(), g.b().id(), "Smuggled"))
                .rootCause().hasMessageContaining("row-level security");
        // boundary: the same insert for the caller's own tenant still works
        insertPatient(g.a().id(), g.a().id(), "Own");
        assertThat(patientNamesVisibleFrom(g.a().id())).containsExactlyInAnyOrder("Patient A", "Own");
    }

    @ParameterizedTest
    @ValueSource(strings = {"FACILITY", "BRAND", "TENANT"})
    void updatingARowIntoAnotherTenantFailsAndSiblingRowsAreUntouchable(String scope) {
        Group g = group("upd-" + scope.toLowerCase() + "@a.com", scope);

        assertThatThrownBy(() -> inTenantTx(g.a().id(), () -> jdbc.update(
                "UPDATE patients SET tenant_id = ? WHERE name = 'Patient A'", g.b().id())))
                .rootCause().hasMessageContaining("row-level security");

        int touched = inTenantTx(g.a().id(), () -> jdbc.update("UPDATE patients SET name = 'Hacked' WHERE name = 'Patient B'"));
        int deleted = inTenantTx(g.a().id(), () -> jdbc.update("DELETE FROM patients WHERE name = 'Patient B'"));
        assertThat(touched).isZero();
        assertThat(deleted).isZero();
        assertThat(patientNamesVisibleFrom(g.b().id())).containsExactly("Patient B");
    }

    @Test
    void allTwentyWidenedTablesLostTheAccessibleIdsClauseAndHaveWritePolicies() {
        for (String t : WIDENED) {
            List<String> defs = jdbc.query("SELECT coalesce(qual,'') || coalesce(with_check,'') FROM pg_policies " +
                    "WHERE tablename = ?", (rs, i) -> rs.getString(1), t);
            assertThat(defs).as(t).isNotEmpty();
            assertThat(String.join(" ", defs)).as(t).doesNotContain("accessible_tenant_ids");
            boolean singleOnly = t.equals("roles") || t.equals("staff") || t.equals("sessions");
            assertThat(String.join(" ", defs).contains("visible_tenant_ids")).as(t + " widens?").isEqualTo(!singleOnly);
            if (!singleOnly) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_policies WHERE tablename = ? AND cmd IN ('INSERT','UPDATE') AND with_check IS NOT NULL",
                        Integer.class, t)).as(t + " WITH CHECK policies").isEqualTo(2);
            }
        }
    }

    @Test
    void m0InventoryListsEveryNonFacilityBrandWithItsOwner() throws Exception {
        Group g = group("m0-owner@a.com", "BRAND");
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of("../scripts/plt018-m0-brand-inventory.sql"));
        var rows = jdbc.queryForList(sql);
        var mine = rows.stream().filter(r -> "m0-owner@a.com".equals(String.valueOf(r.get("owner_email")))).toList();
        assertThat(mine).hasSize(1); // brand X (BRAND); brand Y stays FACILITY and is not listed
        assertThat(mine.get(0)).containsEntry("data_visibility_scope", "BRAND").containsEntry("facilities", 2L);
        assertThat(rows).noneMatch(r -> "FACILITY".equals(r.get("data_visibility_scope")));
    }
}
