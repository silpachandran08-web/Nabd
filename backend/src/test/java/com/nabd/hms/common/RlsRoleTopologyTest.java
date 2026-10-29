package com.nabd.hms.common;

import com.nabd.hms.support.ApiTestBase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** OPS-003 criteria 3, 4 (database side), 5, 7 and 8's FORCE half: what the split roles can and cannot do. */
class RlsRoleTopologyTest extends ApiTestBase {

    @Autowired
    HikariDataSource pool;

    private void insertPatient(UUID tenantId, String name) {
        inTenantTx(tenantId, () -> jdbc.update(
                "INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,?,'+919000000000','1990-01-01','other')",
                tenantId, name));
    }

    /** Runs work in one tx with the GUCs exactly as the story states them: own tenant A, visible {A,B}. */
    private <T> T asTenantWithVisible(UUID own, String visibleCsv, java.util.function.Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(s -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true) || set_config('app.visible_tenant_ids', ?, true)",
                    String.class, own.toString(), visibleCsv);
            return work.get();
        });
    }

    @Test
    void aWidenedVisibleListNoLongerShowsOrTouchesSiblingRows() {
        // PLT-021 (V2521) removed brand visibility: policies are single-tenant whatever app.visible_tenant_ids holds.
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        insertPatient(b.id(), "Patient B");
        String both = a.id() + "," + b.id();

        assertThat(asTenantWithVisible(a.id(), both, () -> jdbc.queryForList("SELECT name FROM patients", String.class)))
                .as("sibling row is not visible").isEmpty();
        assertThat(asTenantWithVisible(a.id(), both, () -> jdbc.update("UPDATE patients SET name = 'hijacked' WHERE tenant_id = ?", b.id())))
                .as("update cannot reach it").isZero();
        assertThatThrownBy(() -> asTenantWithVisible(a.id(), both, () -> jdbc.update(
                "INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,'x','+919000000001','1990-01-01','other')", b.id())))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("row-level security");
        assertThat(asTenantWithVisible(a.id(), both, () -> jdbc.update("DELETE FROM patients WHERE tenant_id = ?", b.id())))
                .as("delete is own-tenant only").isZero();
        assertThat(inTenantTx(b.id(), () -> jdbc.queryForObject("SELECT name FROM patients", String.class)))
                .as("B's row untouched").isEqualTo("Patient B");
    }

    @Test
    void ownTenantWritesStillWork() {
        SeededTenant a = seedTenant();
        insertPatient(a.id(), "Patient A");
        assertThat(inTenantTx(a.id(), () -> jdbc.update("UPDATE patients SET name = 'Renamed' WHERE tenant_id = ?", a.id()))).isEqualTo(1);
        assertThat(inTenantTx(a.id(), () -> jdbc.update("DELETE FROM patients WHERE tenant_id = ?", a.id()))).isEqualTo(1);
    }

    @Test
    void noTenantContextMeansNoRowsNotAnError() {
        SeededTenant a = seedTenant();
        insertPatient(a.id(), "Patient A");
        // a pooled connection that earlier ran set_config(..., true) holds '' for the GUC; that must read as "no tenant"
        inTenantTx(a.id(), () -> jdbc.queryForObject("SELECT 1", Integer.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patients", Integer.class)).isZero();
    }

    @Test
    void forceRlsBindsTheTableOwnerToo() {
        SeededTenant a = seedTenant();
        insertPatient(a.id(), "Patient A");
        JdbcTemplate migrator = new JdbcTemplate(dataSourceFor(MIGRATOR_ROLE, MIGRATOR_ROLE_PW));
        JdbcTemplate superuser = new JdbcTemplate(superuserDataSource());
        assertThat(superuser.queryForObject("SELECT count(*) FROM patients WHERE tenant_id = ?", Integer.class, a.id())).isEqualTo(1);
        assertThat(migrator.queryForObject("SELECT count(*) FROM patients WHERE tenant_id = ?", Integer.class, a.id()))
                .as("owner without BYPASSRLS sees nothing: FORCE is on").isZero();
    }

    @Test
    void roleAttributesAndTimeoutsAreAsSpecified() {
        assertThat(jdbc.queryForMap("SELECT rolsuper, rolbypassrls, rolcreaterole FROM pg_roles WHERE rolname = 'nabd_app'"))
                .containsEntry("rolsuper", false).containsEntry("rolbypassrls", false);
        assertThat(jdbc.queryForObject("SELECT rolbypassrls FROM pg_roles WHERE rolname = 'nabd_migrator'", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT rolcanlogin FROM pg_roles WHERE rolname = 'nabd_definer'", Boolean.class)).isFalse();
        assertThat(jdbc.queryForList("SELECT unnest(setconfig) FROM pg_db_role_setting s JOIN pg_roles r ON r.oid = s.setrole WHERE r.rolname = 'nabd_app'", String.class))
                .contains("statement_timeout=5s", "lock_timeout=3s", "idle_in_transaction_session_timeout=15s");
        JdbcTemplate reports = new JdbcTemplate(dataSourceFor(REPORTS_ROLE, REPORTS_ROLE_PW));
        assertThat(reports.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("1min");
        assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("5s");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_class WHERE relowner = (SELECT oid FROM pg_roles WHERE rolname = 'nabd_app')", Integer.class)).isZero();
        assertThatThrownBy(() -> reports.update("DELETE FROM tenants")).isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void appRoleStatementIsCancelledAtFiveSeconds() {
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT pg_sleep(7)::text", String.class))
                .hasMessageContaining("statement timeout");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(ms).as("cancelled by the 5 s role timeout, not run to 7 s").isBetween(4_500L, 6_500L);
        JdbcTemplate reports = new JdbcTemplate(dataSourceFor(REPORTS_ROLE, REPORTS_ROLE_PW));
        assertThat(reports.queryForObject("SELECT pg_sleep(6)::text", String.class)).as("reports role allows 60 s").isNotNull();
    }

    @Test
    void appCannotReadOwnerPinHashDirectlyButOwnerLookupStillWorks() {
        SeededOwner owner = seedOwner("pin-lookup@a.com");
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT pin_hash FROM owners WHERE id = ?", String.class, owner.id()))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.queryForList("SELECT * FROM owners"))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
        assertThat(jdbc.queryForObject("SELECT name FROM owners WHERE id = ?", String.class, owner.id())).isNotNull(); // other columns still readable
        assertThat(jdbc.queryForObject("SELECT pin_hash FROM owner_auth_lookup(p_email => ?::citext)", String.class, owner.email())).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT pin_hash FROM owner_auth_lookup(p_id => ?)", String.class, owner.id())).isNotBlank();
        assertThat(jdbc.queryForList("SELECT 1 FROM owner_auth_lookup()")).as("no key given -> no rows").isEmpty();
        assertThat(jdbc.queryForList("SELECT 1 FROM owner_auth_lookup(p_email => ?::citext, p_id => ?)", owner.email(), owner.id()))
                .as("two keys -> no rows").isEmpty();
        assertThat(jdbc.queryForObject("SELECT pg_get_userbyid(proowner) FROM pg_proc WHERE proname = 'owner_auth_lookup'", String.class)).isEqualTo("nabd_definer");
    }

    @Test
    void ownerLevelTablesAreClosedToTheAppRole() {
        assertThatThrownBy(() -> jdbc.queryForList("SELECT * FROM legal_entities"))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void packageEligibleDoctorsIsTenantScopedNow() {
        assertThat(jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns WHERE table_name = 'package_eligible_doctors' AND column_name = 'tenant_id'", String.class))
                .isEqualTo("NO");
    }

    @Test
    void hikariIsConfiguredToFailFast() {
        assertThat(pool.getMaximumPoolSize()).isBetween(15, 20);
        assertThat(pool.getMinimumIdle()).isEqualTo(pool.getMaximumPoolSize());
        assertThat(pool.getConnectionTimeout()).isEqualTo(3_000L);
        assertThat(pool.getMaxLifetime()).isEqualTo(30L * 60_000L);
        assertThat(pool.getLeakDetectionThreshold()).isEqualTo(10_000L);
    }
}
