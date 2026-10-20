package com.nabd.hms.platform;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Independent QA (S9 PLT-018), flag OFF (the default): brand visibility contained, writes pinned, hot query fast. */
class Plt018ContainmentS9QaTest extends ApiTestBase {

    private static final List<String> WIDENED_READ = List.of("patients", "doctor_working_hours", "doctor_leave", "appointments", "queue_entries",
            "consents", "patient_merges", "clinical_notes", "clinic_setup_progress", "charge_catalogue", "clinic_policies", "clinic_holidays",
            "staff_shifts", "attendance_records", "licence_registry", "data_import_jobs", "data_export_jobs",
            "day_closes", "day_close_events"); // FIN-005 (S12): ADR-07 widened read on purpose
    private static final List<String> NEVER_WIDE = List.of("roles", "staff", "sessions");

    private record Pair(SeededTenant a, SeededTenant b) {}

    private Pair brandPair(String email, String scope) {
        SeededOwner owner = seedOwner(email);
        SeededBrand x = seedBrand(owner, "QA " + email);
        jdbc.update("UPDATE brands SET data_visibility_scope = ? WHERE id = ?", scope, x.id());
        return new Pair(seedClinicInBrand(x), seedClinicInBrand(x));
    }

    private void patient(UUID session, UUID row, String name) {
        inTenantTx(session, () -> jdbc.update("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,?,'+919000000000','1990-01-01','other')", row, name));
    }

    private void holiday(UUID session, UUID row, String day) {
        inTenantTx(session, () -> jdbc.update("INSERT INTO clinic_holidays (tenant_id, holiday_date, name) VALUES (?,?::date,'QA')", row, day));
    }

    // AC1 ---------------------------------------------------------------------------------------
    @Test
    void ac1_brandScopeFlagOff_httpListAndDirectSqlSeeOnlyOwnFacility() {
        for (String scope : new String[]{"BRAND", "TENANT"}) {
            Pair p = brandPair("qa18-" + scope + "@a.com", scope);
            patient(p.a().id(), p.a().id(), "OnlyA-" + scope);
            patient(p.b().id(), p.b().id(), "OnlyB-" + scope);
            SeededStaff sa = seedStaff(p.a(), roleOf(p.a()), "qa18a-" + scope + "@a.com", "+919400300001", false);
            ResponseEntity<Map> r = exchange("/v1/patients?limit=50", HttpMethod.GET, authed(loginAndGetAccessToken(sa)), Map.class);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(r.getBody().toString()).contains("OnlyA-" + scope).doesNotContain("OnlyB-" + scope);
            assertThat(inTenantTx(p.a().id(), () -> jdbc.queryForObject("SELECT current_setting('app.visible_tenant_ids')", String.class))).isEqualTo(p.a().id().toString());
            for (String table : List.of("patients", "roles", "staff", "departments")) {
                assertThat(inTenantTx(p.a().id(), () -> jdbc.queryForList("SELECT DISTINCT tenant_id FROM " + table, UUID.class)))
                        .as("%s visible from A (%s)", table, scope).isSubsetOf(p.a().id());
            }
        }
    }

    private UUID roleOf(SeededTenant t) {
        return inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT id FROM roles WHERE built_in", UUID.class));
    }

    @Test
    void ac1_noTenantContextAndStaleGucAreEmptyNotErrors() {
        // a pooled connection's GUC reverts to '' after a transaction; a bare statement must see no rows and must not throw
        for (String table : List.of("patients", "staff", "roles", "sessions", "queue_entries", "clinic_holidays")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).as(table).isZero();
        }
    }

    // AC2 ---------------------------------------------------------------------------------------
    @Test
    void ac2_writesIntoASiblingFailForInsertUpdateAndMoveOnPatientsAndASetupTable_deleteAndUpdateOfSiblingRowsAffectNothing() {
        for (String scope : new String[]{"FACILITY", "BRAND", "TENANT"}) {
            Pair p = brandPair("qa18w-" + scope + "@a.com", scope);
            patient(p.a().id(), p.a().id(), "A");
            patient(p.b().id(), p.b().id(), "B");
            holiday(p.b().id(), p.b().id(), "2030-01-01");
            // insert with the sibling's tenant_id
            assertThatThrownBy(() -> patient(p.a().id(), p.b().id(), "Smuggled")).as(scope).rootCause().hasMessageContaining("row-level security");
            assertThatThrownBy(() -> holiday(p.a().id(), p.b().id(), "2030-02-02")).as(scope).rootCause().hasMessageContaining("row-level security");
            // move my own row into the sibling
            assertThatThrownBy(() -> inTenantTx(p.a().id(), () -> jdbc.update("UPDATE patients SET tenant_id = ? WHERE name = 'A'", p.b().id())))
                    .as(scope).rootCause().hasMessageContaining("row-level security");
            // sibling rows are untouchable
            assertThat(inTenantTx(p.a().id(), () -> jdbc.update("UPDATE patients SET name = 'X' WHERE name = 'B'"))).isZero();
            assertThat(inTenantTx(p.a().id(), () -> jdbc.update("DELETE FROM patients WHERE name = 'B'"))).isZero();
            assertThat(inTenantTx(p.a().id(), () -> jdbc.update("DELETE FROM clinic_holidays"))).isZero();
            assertThat(inTenantTx(p.b().id(), () -> jdbc.queryForList("SELECT name FROM patients", String.class))).containsExactly("B");
            assertThat(inTenantTx(p.b().id(), () -> jdbc.queryForObject("SELECT count(*) FROM clinic_holidays", Integer.class))).isEqualTo(1);
        }
    }

    @Test
    void ac2_catalogueOfPoliciesOnAllTwentyTables() {
        List<String> problems = new ArrayList<>();
        try (Connection su = superuserConnection(); Statement st = su.createStatement()) {
            for (String t : concat(WIDENED_READ, NEVER_WIDE)) {
                Map<String, String[]> byCmd = new java.util.HashMap<>();
                try (ResultSet rs = st.executeQuery("SELECT cmd, coalesce(qual,''), coalesce(with_check,'') FROM pg_policies WHERE schemaname='public' AND tablename='" + t + "'")) {
                    while (rs.next()) {
                        byCmd.put(rs.getString(1), new String[]{rs.getString(2), rs.getString(3)});
                    }
                }
                try (ResultSet rs = st.executeQuery("SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE oid = 'public." + t + "'::regclass")) {
                    rs.next();
                    if (!rs.getBoolean(1) || !rs.getBoolean(2)) {
                        problems.add(t + ": RLS not enabled+forced");
                    }
                }
                String all = byCmd.values().stream().map(a -> a[0] + a[1]).reduce("", String::concat);
                if (all.contains("accessible_tenant_ids")) {
                    problems.add(t + ": still references app.accessible_tenant_ids");
                }
                if (NEVER_WIDE.contains(t) && all.contains("visible_tenant_ids")) {
                    problems.add(t + ": widened by visible_tenant_ids");
                }
                if (!NEVER_WIDE.contains(t)) {
                    String sel = byCmd.containsKey("SELECT") ? byCmd.get("SELECT")[0] : "";
                    if (!sel.contains("visible_tenant_ids") || sel.contains(" OR ")) {
                        problems.add(t + ": SELECT is not the plain array form: " + sel);
                    }
                }
                for (String cmd : new String[]{"INSERT", "UPDATE"}) {
                    String check = byCmd.containsKey(cmd) ? byCmd.get(cmd)[1] : "";
                    if (check.isEmpty() || check.contains("visible_tenant_ids") || !check.contains("tenant")) {
                        problems.add(t + ": " + cmd + " WITH CHECK is missing or reads the widened set: '" + check + "'");
                    }
                }
                String del = byCmd.containsKey("DELETE") ? byCmd.get("DELETE")[0] : "";
                if (del.isEmpty() || del.contains("visible_tenant_ids")) {
                    problems.add(t + ": DELETE is missing or widened: '" + del + "'");
                }
            }
            // nothing OUTSIDE the 17 widens (no accidental new widening), and nothing anywhere still uses the old GUC
            List<String> widens = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT DISTINCT tablename FROM pg_policies WHERE schemaname='public' AND cmd IN ('SELECT','ALL') AND qual LIKE '%visible_tenant_ids%'")) {
                while (rs.next()) {
                    widens.add(rs.getString(1));
                }
            }
            assertThat(widens).as("tables whose read policy widens").containsExactlyInAnyOrderElementsOf(WIDENED_READ);
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_policies WHERE qual LIKE '%accessible_tenant_ids%' OR with_check LIKE '%accessible_tenant_ids%'")) {
                rs.next();
                assertThat(rs.getInt(1)).as("policies on any table still using app.accessible_tenant_ids").isZero();
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        assertThat(problems).isEmpty();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> r = new ArrayList<>(a);
        r.addAll(b);
        return r;
    }

    // AC3 ---------------------------------------------------------------------------------------
    @Test
    void ac3_staffSessionsRolesNeverWidenAndTenantContextSourceHasNoOtherWideningPath() throws Exception {
        Pair p = brandPair("qa18s@a.com", "BRAND");
        seedStaff(p.b(), roleOf(p.b()), "qa18s-b@a.com", "+919400300010", false);
        SeededStaff sa = seedStaff(p.a(), roleOf(p.a()), "qa18s-a@a.com", "+919400300011", false);
        loginAndGetAccessToken(sa);
        for (String table : NEVER_WIDE) {
            assertThat(inTenantTx(p.a().id(), () -> jdbc.queryForList("SELECT DISTINCT tenant_id FROM " + table, UUID.class))).as(table).containsOnly(p.a().id());
        }
        String src = Files.readString(Path.of("src/main/java/com/nabd/hms/common/TenantContext.java"));
        assertThat(src).contains("nabd.legacy-brand-visibility:false");
        for (String f : List.of("src/main/resources/application.yml")) {
            assertThat(Files.readString(Path.of(f))).as(f + " must not switch the flag on").doesNotContainPattern("legacy-brand-visibility\\s*[:=]\\s*true");
        }
        try (var ds = Files.newDirectoryStream(Path.of("src/main/resources"), "application*")) {
            for (Path f : ds) {
                assertThat(Files.readString(f)).as(f + " flag default").doesNotContainPattern("legacy-brand-visibility\\s*[:=]\\s*true");
            }
        }
    }

    // AC4 ---------------------------------------------------------------------------------------
    @Test
    void ac4_nextTokenNumberIsAnIndexScanAndUnderFiveMsOn500kPatients_withBrandScopeTenantsInTheSameTable() throws Exception {
        String repoSrc = Files.readString(Path.of("src/main/java/com/nabd/hms/queue/QueueRepository.java"));
        String sql = "SELECT COALESCE(MAX(token_number), 0) FROM queue_entries WHERE tenant_id = ? AND doctor_id = ? AND queue_date = ?";
        assertThat(repoSrc).as("production query still has the explicit tenant predicate").contains(sql);

        Pair p = brandPair("qa18p@a.com", "BRAND");
        SeededTenant a = p.a();
        UUID doctor = UUID.randomUUID();
        UUID dept = inTenantTx(a.id(), () -> jdbc.queryForObject("SELECT id FROM departments LIMIT 1", UUID.class));
        UUID deptB = inTenantTx(p.b().id(), () -> jdbc.queryForObject("SELECT id FROM departments LIMIT 1", UUID.class));
        try (Connection su = superuserConnection(); Statement st = su.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute("INSERT INTO patients (id, tenant_id, name, phone, dob, gender) SELECT gen_random_uuid(), '" + a.id()
                    + "', 'P' || n, '+9190' || lpad(n::text, 8, '0'), date '1990-01-01', 'other' FROM generate_series(1, 500000) n");
            st.execute("INSERT INTO queue_entries (id, tenant_id, patient_id, doctor_id, department_id, encounter_id, queue_date, token_number, status, current_stage) " +
                    "SELECT id, CASE WHEN n % 3 = 0 THEN '" + p.b().id() + "'::uuid ELSE '" + a.id() + "'::uuid END, gen_random_uuid(), " +
                    "CASE WHEN n % 500 = 0 THEN '" + doctor + "'::uuid ELSE gen_random_uuid() END, '" + dept + "', id, date '2026-01-01' + (n % 100), n, 'completed', stage_for_status('completed') " +
                    "FROM (SELECT gen_random_uuid() AS id, n FROM generate_series(1, 500000) n) s");
            st.execute("ANALYZE patients");
            st.execute("ANALYZE queue_entries");
        }
        assertThat(deptB).isNotNull();
        String q = sql.replaceFirst("tenant_id = \\?", "tenant_id = '" + a.id() + "'").replaceFirst("doctor_id = \\?", "doctor_id = '" + doctor + "'")
                .replaceFirst("queue_date = \\?", "queue_date = date '2026-01-01'");
        List<Double> times = new ArrayList<>();
        String lastPlan = "";
        for (int i = 0; i < 9; i++) {
            StringBuilder plan = new StringBuilder();
            inTenantTx(a.id(), () -> {
                jdbc.query("EXPLAIN (ANALYZE, FORMAT TEXT) " + q, (ResultSet rs) -> { plan.append(rs.getString(1)).append('\n'); });
            });
            Matcher m = Pattern.compile("Execution Time: ([0-9.]+) ms").matcher(plan);
            assertThat(m.find()).isTrue();
            times.add(Double.parseDouble(m.group(1)));
            lastPlan = plan.toString();
        }
        java.util.Collections.sort(times);
        double median = times.get(times.size() / 2);
        System.out.println("PLT018-QA nextTokenNumber exec ms sorted=" + times + " median=" + median + "\n" + lastPlan);
        assertThat(lastPlan).doesNotContain("Seq Scan").contains("Index");
        assertThat(lastPlan).as("tenant must be an index condition, not a filter").containsPattern("Index Cond: .*tenant_id");
        assertThat(median).as(lastPlan).isLessThanOrEqualTo(5.0);
        assertThat(inTenantTx(a.id(), () -> jdbc.queryForObject("SELECT count(*) FROM patients", Integer.class))).isGreaterThanOrEqualTo(500000);
    }

    // AC5 (local part) --------------------------------------------------------------------------
    @Test
    void ac5_m0InventoryScriptListsEveryNonFacilityBrandWithItsOwnerAndNothingElse() throws Exception {
        String script = Files.readString(Path.of("../scripts/plt018-m0-brand-inventory.sql"));
        assertThat(script).doesNotContainIgnoringCase("insert ").doesNotContainIgnoringCase("update ").doesNotContainIgnoringCase("delete ");
        SeededOwner o = seedOwner("qa18m0@a.com");
        SeededBrand fac = seedBrand(o, "QA-FAC");
        SeededBrand br = seedBrand(o, "QA-BRAND");
        SeededBrand te = seedBrand(o, "QA-TENANT");
        jdbc.update("UPDATE brands SET data_visibility_scope='BRAND' WHERE id=?", br.id());
        jdbc.update("UPDATE brands SET data_visibility_scope='TENANT' WHERE id=?", te.id());
        seedClinicInBrand(br);
        seedClinicInBrand(br);
        Set<String> got = new java.util.HashSet<>();
        try (Connection su = superuserConnection(); Statement st = su.createStatement(); ResultSet rs = st.executeQuery(script.replaceAll(";\\s*$", ""))) {
            while (rs.next()) {
                String name = rs.getString("brand_name");
                if (name.startsWith("QA-")) {
                    got.add(name + "|" + rs.getString("data_visibility_scope") + "|" + rs.getString("owner_email") + "|" + rs.getInt("facilities"));
                }
            }
        }
        assertThat(got).containsExactlyInAnyOrder("QA-BRAND|BRAND|qa18m0@a.com|2", "QA-TENANT|TENANT|qa18m0@a.com|0");
        assertThat(fac.id()).isNotNull();
    }

    // hot-query predicates (static) ------------------------------------------------------------
    @Test
    void hotQueriesCarryAnExplicitTenantPredicate() throws Exception {
        String sched = Files.readString(Path.of("src/main/java/com/nabd/hms/queue/ScheduleRepository.java"));
        int i = sched.indexOf("countSessionOccupancy");
        assertThat(i).isPositive();
        String body = sched.substring(i, Math.min(sched.length(), i + 1500));
        assertThat(body).as("countSessionOccupancy").contains("tenant_id");
    }
}
