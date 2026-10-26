package com.nabd.hms.organization.setup;

import com.nabd.hms.support.ApiTestBase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADM-007: V2500 maps the Sprint 5 step values onto the nine new steps (a scratch database migrated to V2290 first, then to the latest). */
class Adm007StepMigrationTest extends ApiTestBase {

    private static final String DB = "adm007_mig";

    private Map<String, String> steps(Connection c, UUID tenant) throws Exception {
        Map<String, String> out = new HashMap<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT step, status FROM clinic_setup_progress WHERE tenant_id = '" + tenant + "'")) {
            while (rs.next()) out.put(rs.getString(1), rs.getString(2));
        }
        return out;
    }

    @Test
    void oldStepValuesAreMappedAndMergedAndThePolicyIsOwnClinicOnly() throws Exception {
        String base = superuserConnection().getMetaData().getURL();
        try (Connection admin = superuserConnection(); Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + DB);
            st.execute("CREATE DATABASE " + DB + " OWNER " + MIGRATOR_ROLE);
        }
        String url = base.substring(0, base.lastIndexOf('/') + 1) + DB;
        try (Connection c = su(url); Statement st = c.createStatement()) { // as S10QaOps025: the migrator needs the public schema of a fresh database
            st.execute("ALTER SCHEMA public OWNER TO " + MIGRATOR_ROLE);
            st.execute("GRANT USAGE, CREATE ON SCHEMA public TO nabd_definer");
        }
        Flyway.configure().dataSource(url, MIGRATOR_ROLE, MIGRATOR_ROLE_PW).locations("filesystem:../db/migrations").target("2290").load().migrate();

        UUID t1 = UUID.randomUUID(), t2 = UUID.randomUUID(), t3 = UUID.randomUUID();
        try (Connection c = su(url); Statement st = c.createStatement()) {
            for (UUID t : new UUID[]{t1, t2, t3}) {
                st.execute("INSERT INTO tenants (id, slug, name, region_code, status, timezone, data_gate) VALUES ('" + t + "', 's" + t.toString().substring(0, 8) + "', 'T', 'IN', 'active', 'UTC', 'live')");
            }
            String[][] rows = {
                    {t1.toString(), "welcome", "done"}, {t1.toString(), "profile", "done"}, {t1.toString(), "tax", "skipped"}, {t1.toString(), "doctors", "done"},
                    {t1.toString(), "schedule", "done"}, {t1.toString(), "charges", "done"}, {t1.toString(), "pharmacy", "done"}, {t1.toString(), "departments", "pending"},
                    {t1.toString(), "whatsapp", "pending"}, {t1.toString(), "go_live", "pending"},
                    {t2.toString(), "doctors", "done"}, {t2.toString(), "schedule", "pending"}, {t2.toString(), "charges", "skipped"},
                    {t3.toString(), "schedule", "done"}};
            for (String[] r : rows) {
                st.execute("INSERT INTO clinic_setup_progress (tenant_id, step, status, done_at, skipped_at) VALUES ('" + r[0] + "', '" + r[1] + "', '" + r[2] + "', "
                        + ("done".equals(r[2]) ? "now()" : "NULL") + ", " + ("skipped".equals(r[2]) ? "now()" : "NULL") + ")");
            }
        }
        Flyway.configure().dataSource(url, MIGRATOR_ROLE, MIGRATOR_ROLE_PW).locations("filesystem:../db/migrations").load().migrate();

        try (Connection c = su(url)) {
            assertThat(steps(c, t1)).containsOnly(Map.entry("profile", "done"), Map.entry("tax", "skipped"), Map.entry("doctors", "done"), Map.entry("prices", "done"),
                    Map.entry("preset", "pending"), Map.entry("whatsapp", "pending"), Map.entry("go_live", "pending"));
            assertThat(steps(c, t2)).containsOnly(Map.entry("doctors", "pending"), Map.entry("prices", "skipped")); // schedule pending => the merged step is not done
            assertThat(steps(c, t3)).containsOnly(Map.entry("doctors", "done")); // a lone schedule row becomes doctors
            try (Statement st = c.createStatement()) {
                ResultSet rs = st.executeQuery("SELECT count(*) FROM clinic_setup_progress WHERE draft <> '{}'::jsonb OR version <> 0");
                rs.next();
                assertThat(rs.getInt(1)).isZero();
                rs = st.executeQuery("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE relname = 'clinic_setup_progress'");
                rs.next();
                assertThat(rs.getBoolean(1)).isTrue();
                rs = st.executeQuery("SELECT count(*) FROM pg_policies WHERE tablename = 'clinic_setup_progress' AND qual ILIKE '%visible_tenant_ids%'");
                rs.next();
                assertThat(rs.getInt(1)).isZero();
                try {
                    st.execute("INSERT INTO clinic_setup_progress (tenant_id, step) VALUES ('" + t3 + "', 'welcome')");
                    throw new AssertionError("old step name must be refused");
                } catch (java.sql.SQLException expected) {
                    assertThat(expected.getMessage()).contains("clinic_setup_progress_step_check");
                }
            }
        }
    }

    private Connection su(String url) throws Exception {
        var ds = superuserDataSource();
        return DriverManager.getConnection(url, ds.getUsername(), ds.getPassword());
    }
}
