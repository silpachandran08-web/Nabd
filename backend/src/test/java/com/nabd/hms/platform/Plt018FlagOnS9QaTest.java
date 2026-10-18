package com.nabd.hms.platform;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Independent QA (S9 PLT-018), flag ON + allow-list: reads widen for the listed brand only, writes into siblings still fail. */
@TestPropertySource(properties = {
        "nabd.legacy-brand-visibility=true",
        "nabd.legacy-brand-visibility.allowed-brand-ids=00000000-0000-0000-0000-0000000018bb"})
class Plt018FlagOnS9QaTest extends ApiTestBase {

    private static final UUID LISTED = UUID.fromString("00000000-0000-0000-0000-0000000018bb");

    private SeededTenant clinic(UUID brandId) {
        SeededTenant t = seedTenant();
        jdbc.update("UPDATE tenants SET brand_id = ? WHERE id = ?", brandId, t.id());
        seedFullAccessRole(t.id());
        return t;
    }

    private void patient(UUID session, UUID row, String name) {
        inTenantTx(session, () -> jdbc.update("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,?,'+919000000000','1990-01-01','other')", row, name));
    }

    @Test
    void ac6_listedBrandWidensReadsOnly_unlistedBrandAndNeverWideTablesStayNarrow() {
        SeededOwner owner = seedOwner("qa18f@a.com");
        jdbc.update("INSERT INTO brands (id, owner_id, name, status, data_visibility_scope) VALUES (?,?,?,'active','BRAND')", LISTED, owner.id(), "Listed");
        SeededBrand unlisted = seedBrand(owner, "Unlisted");
        jdbc.update("UPDATE brands SET data_visibility_scope='BRAND' WHERE id=?", unlisted.id());
        SeededTenant a = clinic(LISTED), b = clinic(LISTED), u1 = clinic(unlisted.id()), u2 = clinic(unlisted.id());
        patient(a.id(), a.id(), "LA");
        patient(b.id(), b.id(), "LB");
        patient(u1.id(), u1.id(), "UA");
        patient(u2.id(), u2.id(), "UB");

        assertThat(inTenantTx(a.id(), () -> jdbc.queryForList("SELECT name FROM patients", String.class))).containsExactlyInAnyOrder("LA", "LB");
        assertThat(inTenantTx(a.id(), () -> jdbc.queryForObject("SELECT current_setting('app.visible_tenant_ids')", String.class)))
                .contains(a.id().toString()).contains(b.id().toString());
        assertThat(inTenantTx(u1.id(), () -> jdbc.queryForList("SELECT name FROM patients", String.class))).containsExactly("UA");

        // writes into the sibling: insert, update-in-place, move, delete
        assertThatThrownBy(() -> patient(a.id(), b.id(), "Smuggled")).rootCause().hasMessageContaining("row-level security");
        assertThatThrownBy(() -> inTenantTx(a.id(), () -> jdbc.update("UPDATE patients SET name = 'Hacked' WHERE name = 'LB'"))).rootCause().hasMessageContaining("row-level security");
        assertThatThrownBy(() -> inTenantTx(a.id(), () -> jdbc.update("UPDATE patients SET tenant_id = ? WHERE name = 'LA'", b.id()))).rootCause().hasMessageContaining("row-level security");
        assertThat(inTenantTx(a.id(), () -> jdbc.update("DELETE FROM patients WHERE name = 'LB'"))).isZero();
        assertThat(inTenantTx(b.id(), () -> jdbc.queryForList("SELECT name FROM patients", String.class))).containsExactlyInAnyOrder("LA", "LB");

        // staff, sessions, roles: no flag value widens them
        for (String table : List.of("staff", "sessions", "roles")) {
            assertThat(inTenantTx(a.id(), () -> jdbc.queryForList("SELECT DISTINCT tenant_id FROM " + table, UUID.class))).as(table).isSubsetOf(a.id());
        }
    }
}
