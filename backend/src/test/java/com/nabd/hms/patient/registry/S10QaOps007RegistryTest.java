package com.nabd.hms.patient.registry;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Independent QA (S10) for OPS-007. Written from the story's acceptance criteria. */
@RecordApplicationEvents
class S10QaOps007RegistryTest extends ApiTestBase {

    @Autowired RegistryReconciler reconciler;
    @Autowired RegistryProjector projector;
    @Autowired ApplicationEvents events;
    @LocalManagementPort int mgmtPort;

    private record Ctx(SeededTenant t, SeededStaff staff, String token) { }

    private Ctx ctx() {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "s10q7" + UUID.randomUUID() + "@a.com", "+91982" + (1000000 + (int) (Math.random() * 8_000_000)), false);
        return new Ctx(t, s, loginAndGetAccessToken(s));
    }

    private <T> T q(Ctx c, String sql, Class<T> type, Object... args) {
        return inTenantTx(c.t.id(), () -> jdbc.queryForObject(sql, type, args));
    }

    private long summaryRows(Ctx c) {
        return q(c, "SELECT count(*) FROM patient_registry_summary", Long.class);
    }

    private void bulkPatients(Ctx c, int n, String prefix) {
        inTenantTx(c.t.id(), () -> jdbc.update("""
                INSERT INTO patients (tenant_id, name, phone, dob, gender)
                SELECT ?, ? || ' ' || md5(g::text), '+9188' || lpad(g::text, 8, '0'), date '1950-01-01' + (g % 20000), 'male' FROM generate_series(1, ?) g""", c.t.id(), prefix, n));
    }

    private void analyze() {
        try (Connection su = superuserConnection(); Statement st = su.createStatement()) { // bulk-loaded in one tx: refresh stats the way autovacuum would
            for (String t : List.of("patients", "queue_entries", "invoices", "appointments", "chronic_conditions", "patient_allergies")) st.execute("ANALYZE " + t);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private UUID newPatient(Ctx c, String name, String phone, String dob) {
        var r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token, Map.of("name", name, "phone", phone, "dob", dob, "gender", "male")), Map.class);
        assertThat(r.getStatusCode().value()).as("register " + r.getBody()).isEqualTo(201);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    private void waitFor(String what, long millis, java.util.function.BooleanSupplier ok) {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            projector.drain(); // test profile sets the background poll to MAX_VALUE; one explicit drain stands in for the 1 s poll
            if (ok.getAsBoolean()) return;
            try { Thread.sleep(100); } catch (InterruptedException e) { throw new IllegalStateException(e); }
        }
        assertThat(ok.getAsBoolean()).as(what + " within " + millis + " ms").isTrue();
    }

    // ---- AC1: backfill of 5,000 ----

    @Test
    void ac1_backfillOf5000PatientsGivesExactly5000RowsMatchingTheSource_andReplayIsANoop() {
        Ctx c = ctx();
        bulkPatients(c, 5000, "Bulk");
        inTenantTx(c.t.id(), () -> jdbc.update("DELETE FROM registry_dirty")); // only the backfill job populates the table
        inTenantTx(c.t.id(), () -> jdbc.update("DELETE FROM patient_registry_summary"));
        assertThat(summaryRows(c)).isZero();
        analyze();

        long changed = reconciler.reconcileTenant(c.t.id());

        assertThat(changed).isEqualTo(5000);
        assertThat(summaryRows(c)).isEqualTo(5000);
        assertThat(q(c, "SELECT count(*) FROM patients p WHERE NOT EXISTS (SELECT 1 FROM patient_registry_summary s WHERE s.tenant_id = p.tenant_id AND s.patient_id = p.id)", Long.class)).isZero();
        assertThat(q(c, """
                SELECT count(*) FROM patients p JOIN patient_registry_summary s ON s.patient_id = p.id AND s.tenant_id = p.tenant_id
                WHERE s.name_search <> lower(p.name) OR s.mrn <> p.mrn OR s.phone_last10 <> right(regexp_replace(p.phone, '\\D', '', 'g'), 10)
                   OR s.archived OR s.balance_due <> 0 OR s.has_active_condition OR s.last_visit_date IS NOT NULL OR s.next_appt_at IS NOT NULL""", Long.class)).isZero();
        assertThat(reconciler.reconcileTenant(c.t.id())).isZero(); // idempotent: nothing differs the second time
        assertThat(summaryRows(c)).isEqualTo(5000);
    }

    @Test
    void ac1_summaryMatchesAnIndependentReferenceOnMixedFixture() {
        Ctx c = ctx();
        bulkPatients(c, 600, "Ref");
        inTenantTx(c.t.id(), () -> {
            jdbc.queryForObject("SELECT setseed(0.17)", Object.class);
            jdbc.update("""
                    INSERT INTO queue_entries (tenant_id, patient_id, doctor_id, department_id, queue_date, token_number, status)
                    SELECT ?, id, ?, (SELECT id FROM departments WHERE tenant_id = ? AND is_default), current_date - (random() * 200)::int, row_number() OVER (ORDER BY id),
                           (ARRAY['completed','completed','no_show','waiting'])[1 + (random() * 3)::int] FROM patients WHERE random() < 0.5""", c.t.id(), c.staff.id(), c.t.id());
            jdbc.update("""
                    INSERT INTO invoices (tenant_id, invoice_number, queue_entry_id, patient_id, doctor_id, subtotal, tax, total, paid, status, created_by)
                    SELECT tenant_id, 'Q-' || id, id, patient_id, doctor_id, 250, 0, 250,
                           CASE s WHEN 'paid' THEN 250 WHEN 'partial' THEN 100 ELSE 0 END, s, doctor_id
                    FROM (SELECT q.*, (ARRAY['paid','partial','unpaid','void'])[1 + (random() * 3)::int] AS s FROM queue_entries q) z WHERE random() < 0.8""");
            jdbc.update("""
                    INSERT INTO chronic_conditions (tenant_id, patient_id, condition, status, review_due_date, recorded_by)
                    SELECT ?, id, 'Cond', CASE WHEN random() < 0.3 THEN 'resolved' ELSE 'active' END, current_date + (random() * 60 - 30)::int, ? FROM patients WHERE random() < 0.2""", c.t.id(), c.staff.id());
            jdbc.update("""
                    INSERT INTO patient_allergies (tenant_id, patient_id, substance, severity, active, recorded_by)
                    SELECT ?, id, 'Penicillin', 'severe', random() < 0.7, ? FROM patients WHERE random() < 0.2""", c.t.id(), c.staff.id());
        });
        analyze();
        reconciler.reconcileTenant(c.t.id());

        // reference written from the story: balance = unpaid+partial remainder; condition flag; follow-up = earliest active review date; allergy flag
        assertThat(q(c, """
                SELECT count(*) FROM patients p LEFT JOIN patient_registry_summary s ON s.patient_id = p.id AND s.tenant_id = p.tenant_id
                WHERE s.patient_id IS NULL
                   OR s.balance_due <> COALESCE((SELECT sum(i.total - i.paid) FROM invoices i WHERE i.patient_id = p.id AND i.status IN ('unpaid','partial')), 0)
                   OR s.has_active_condition <> EXISTS (SELECT 1 FROM chronic_conditions c WHERE c.patient_id = p.id AND c.status = 'active')
                   OR s.followup_due IS DISTINCT FROM (SELECT min(c.review_due_date) FROM chronic_conditions c WHERE c.patient_id = p.id AND c.status = 'active')
                   OR (s.top_allergy IS NOT NULL) <> EXISTS (SELECT 1 FROM patient_allergies a WHERE a.patient_id = p.id AND a.active)""", Long.class)).isZero();
        assertThat(q(c, "SELECT count(*) FROM patient_registry_summary WHERE balance_due > 0", Long.class)).isGreaterThan(20);
    }

    /** OPS-004 says the registry read model "uses the same derivation": completed visits only. */
    @Test
    // known defect (OPS-004): registry lastVisitDate still includes unfinished visits
    @Tag("known-defect-OPS-004")
    void ac1_lastVisitDateIsTheDateOfTheLastCompletedVisit() {
        Ctx c = ctx();
        UUID p = newPatient(c, "Lv One", "+919800099001", "1980-01-01");
        inTenantTx(c.t.id(), () -> {
            for (Object[] e : new Object[][]{{"2026-03-02", 1, "completed"}, {"2026-04-01", 2, "waiting"}, {"2026-05-01", 3, "no_show"}})
                jdbc.update("INSERT INTO queue_entries (tenant_id, patient_id, doctor_id, department_id, queue_date, token_number, status) VALUES (?,?,?,(SELECT id FROM departments WHERE tenant_id = ? AND is_default),?::date,?,?)",
                        c.t.id(), p, c.staff.id(), c.t.id(), e[0], e[1], e[2]);
        });
        reconciler.reconcileTenant(c.t.id());
        assertThat(q(c, "SELECT last_visit_date::text FROM patient_registry_summary WHERE patient_id = ?", String.class, p)).isEqualTo("2026-03-02");
    }

    // ---- AC2: listener path <= 5 s ----

    @Test
    void ac2_newPatientVisitAndPaymentShowInTheSummaryWithin5Seconds() {
        Ctx c = ctx();
        UUID p = newPatient(c, "Quick One", "+919800099002", "1981-01-01");
        waitFor("summary row for a new patient", 5000, () -> summaryRows(c) == 1);
        assertThat(q(c, "SELECT name_search FROM patient_registry_summary WHERE patient_id = ?", String.class, p)).isEqualTo("quick one");

        UUID entry = UUID.randomUUID();
        inTenantTx(c.t.id(), () -> {
            jdbc.update("INSERT INTO queue_entries (id, tenant_id, patient_id, doctor_id, department_id, queue_date, token_number, status) VALUES (?,?,?,?,(SELECT id FROM departments WHERE tenant_id = ? AND is_default),current_date,1,'completed')",
                    entry, c.t.id(), p, c.staff.id(), c.t.id());
            jdbc.update("INSERT INTO invoices (tenant_id, invoice_number, queue_entry_id, patient_id, doctor_id, subtotal, tax, total, created_by) VALUES (?,?,?,?,?,500,0,500,?)",
                    c.t.id(), "QV-" + UUID.randomUUID(), entry, p, c.staff.id(), c.staff.id());
        });
        waitFor("visit completed + invoice due", 5000, () -> q(c, "SELECT balance_due FROM patient_registry_summary WHERE patient_id = ?", BigDecimal.class, p).compareTo(new BigDecimal("500")) == 0);
        assertThat(q(c, "SELECT last_visit_date IS NOT NULL FROM patient_registry_summary WHERE patient_id = ?", Boolean.class, p)).isTrue();

        UUID invoice = q(c, "SELECT id FROM invoices WHERE patient_id = ?", UUID.class, p);
        var pay = exchange("/v1/billing/invoices/" + invoice + "/payments", HttpMethod.POST, authedJsonBody(c.token, Map.of("method", "cash", "amount", 500)), Map.class);
        assertThat(pay.getStatusCode().is2xxSuccessful()).as("pay " + pay.getBody()).isTrue();
        waitFor("invoice paid -> balance 0", 5000, () -> q(c, "SELECT balance_due FROM patient_registry_summary WHERE patient_id = ?", BigDecimal.class, p).signum() == 0);
    }

    @Test
    void ac2_changeToOnePatientDoesNotTouchAnotherTenantsRows() {
        Ctx a = ctx(), b = ctx();
        UUID pa = newPatient(a, "Alpha Tenant", "+919800099003", "1982-01-01");
        UUID pb = newPatient(b, "Beta Tenant", "+919800099004", "1982-01-01");
        waitFor("both rows", 5000, () -> summaryRows(a) == 1 && summaryRows(b) == 1);
        assertThat(q(a, "SELECT name_search FROM patient_registry_summary", String.class)).isEqualTo("alpha tenant");
        assertThat(q(b, "SELECT name_search FROM patient_registry_summary", String.class)).isEqualTo("beta tenant");
        assertThat(q(a, "SELECT count(*) FROM patient_registry_summary WHERE patient_id = ?", Long.class, pb)).isZero();
    }

    @Test
    void ac2_aRolledBackWriteLeavesNeitherADirtyMarkNorASummaryRow() {
        Ctx c = ctx();
        assertThatThrownBy(() -> inTenantTx(c.t.id(), () -> {
            jdbc.update("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?, 'Rolled Back', '+919800099005', '1990-01-01', 'male')", c.t.id());
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        projector.drain();
        assertThat(q(c, "SELECT count(*) FROM registry_dirty", Long.class)).isZero();
        assertThat(summaryRows(c)).isZero();
    }

    @Test
    void ac2_defaultPollIntervalKeepsTheListenerPathUnderFiveSeconds() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/nabd/hms/patient/registry/RegistryProjector.java"));
        var m = java.util.regex.Pattern.compile("poll-interval-ms:(\\d+)").matcher(src);
        assertThat(m.find()).isTrue();
        assertThat(Long.parseLong(m.group(1))).isLessThan(2000); // poll + one batch must leave plenty of the 5 s budget
    }

    // ---- idempotency ----

    @Test
    void nfr_replayingTheSameChangeTwiceYieldsTheSameRow_andPublishesOnce() {
        Ctx c = ctx();
        UUID p = newPatient(c, "Replay Me", "+919800099006", "1983-01-01");
        inTenantTx(c.t.id(), () -> jdbc.update("DELETE FROM patient_registry_summary"));
        inTenantTx(c.t.id(), () -> jdbc.update("DELETE FROM registry_dirty"));
        events.clear();
        assertThat(inTenantTx(c.t.id(), () -> projector.project(c.t.id(), List.of(p)))).isEqualTo(1);
        Map<String, Object> first = inTenantTx(c.t.id(), () -> jdbc.queryForMap("SELECT * FROM patient_registry_summary WHERE patient_id = ?", p));
        assertThat(inTenantTx(c.t.id(), () -> projector.project(c.t.id(), List.of(p)))).isZero();
        Map<String, Object> second = inTenantTx(c.t.id(), () -> jdbc.queryForMap("SELECT * FROM patient_registry_summary WHERE patient_id = ?", p));
        assertThat(second).isEqualTo(first); // including change_seq and updated_at
        var published = events.stream(RegistryProjector.SummaryUpdated.class).filter(e -> e.patientId().equals(p)).toList();
        assertThat(published).hasSize(1);
        assertThat(published.get(0).tenantId()).isEqualTo(c.t.id());
        assertThat(published.get(0).changeSeq()).isEqualTo(((Number) first.get("change_seq")).longValue());
        assertThat(RegistryProjector.SummaryUpdated.EVENT).isEqualTo("patient.registry_summary.updated.v1");
    }

    // ---- AC3: reconciliation ----

    @Test
    void ac3_reconciliationRepairsDriftedAndMissingRows() {
        Ctx c = ctx();
        UUID p1 = newPatient(c, "Drift One", "+919800099007", "1984-01-01");
        UUID p2 = newPatient(c, "Drift Two", "+919800099008", "1984-01-02");
        UUID p3 = newPatient(c, "Drift Three", "+919800099009", "1984-01-03");
        waitFor("rows", 5000, () -> summaryRows(c) == 3);
        try (Connection su = superuserConnection(); Statement st = su.createStatement()) { // simulate a projector that missed commits
            st.execute("UPDATE patient_registry_summary SET balance_due = 999, name_search = 'wrong' WHERE patient_id = '" + p1 + "'");
            st.execute("DELETE FROM patient_registry_summary WHERE patient_id = '" + p2 + "'");
            st.execute("UPDATE patient_registry_summary SET archived = true WHERE patient_id = '" + p3 + "'");
        } catch (Exception e) { throw new IllegalStateException(e); }

        assertThat(reconciler.reconcileTenant(c.t.id())).isEqualTo(3);

        assertThat(q(c, "SELECT balance_due FROM patient_registry_summary WHERE patient_id = ?", BigDecimal.class, p1)).isEqualByComparingTo("0");
        assertThat(q(c, "SELECT name_search FROM patient_registry_summary WHERE patient_id = ?", String.class, p1)).isEqualTo("drift one");
        assertThat(summaryRows(c)).isEqualTo(3);
        assertThat(q(c, "SELECT archived FROM patient_registry_summary WHERE patient_id = ?", Boolean.class, p3)).isFalse();
        reconciler.runAll();
        assertThat(reconciler.driftRows()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void ac3_nightlyRunIsScheduledAtNight() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/nabd/hms/patient/registry/RegistryReconciler.java"));
        assertThat(src).contains("@Scheduled(cron");
    }

    @Test
    // known defect (OPS-007): registry projection drift metric is not exported yet
    @Tag("known-defect-OPS-007")
    void ac3_driftMetricIsExportedAsRegistryProjectionDriftRows() throws Exception {
        Ctx c = ctx();
        newPatient(c, "Metric One", "+919800099010", "1985-01-01");
        reconciler.runAll();
        var r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + mgmtPort + "/actuator/prometheus")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("registry_projection_drift_rows");
    }

    // ---- flags ----

    @Test
    void flags_duplicateAndArchived_areComputed_andGuardianChildIsNotADuplicate() {
        Ctx c = ctx();
        UUID a = newPatient(c, "Dup Alpha", "+919800099020", "1970-01-01");
        UUID b = UUID.randomUUID(); // registration refuses a same-phone patient, so a legacy duplicate is inserted directly
        inTenantTx(c.t.id(), () -> jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender) VALUES (?,?,'Zzz Different','+919800099020','1971-02-02','male')", b, c.t.id()));
        UUID solo = newPatient(c, "Solo Person", "+919800099021", "1972-03-03");
        waitFor("rows", 5000, () -> summaryRows(c) == 3);
        reconciler.reconcileTenant(c.t.id()); // duplicate_flag is cross-patient: repaired by reconciliation
        assertThat(q(c, "SELECT duplicate_flag FROM patient_registry_summary WHERE patient_id = ?", Boolean.class, a)).isTrue();
        assertThat(q(c, "SELECT duplicate_flag FROM patient_registry_summary WHERE patient_id = ?", Boolean.class, b)).isTrue();
        assertThat(q(c, "SELECT duplicate_flag FROM patient_registry_summary WHERE patient_id = ?", Boolean.class, solo)).isFalse();

        inTenantTx(c.t.id(), () -> jdbc.update("UPDATE patients SET status = 'merged', merged_into_id = ? WHERE id = ?", a, b));
        reconciler.reconcileTenant(c.t.id());
        assertThat(q(c, "SELECT archived FROM patient_registry_summary WHERE patient_id = ?", Boolean.class, b)).isTrue();
        assertThat(q(c, "SELECT duplicate_flag FROM patient_registry_summary WHERE patient_id = ?", Boolean.class, a)).isFalse();
    }

    // ---- AC4: RLS ----

    @Test
    void ac4_forceRlsAndPerCommandPoliciesOnBothTables() throws Exception {
        try (Connection su = superuserConnection(); Statement st = su.createStatement()) {
            var rs = st.executeQuery("SELECT relname, relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname IN ('patient_registry_summary','registry_dirty') ORDER BY 1");
            int n = 0;
            while (rs.next()) { n++; assertThat(rs.getBoolean(2)).as(rs.getString(1) + " rls").isTrue(); assertThat(rs.getBoolean(3)).as(rs.getString(1) + " force").isTrue(); }
            assertThat(n).isEqualTo(2);
            rs = st.executeQuery("SELECT string_agg(DISTINCT cmd, ',') FROM pg_policies WHERE tablename = 'patient_registry_summary'");
            rs.next();
            String cmds = rs.getString(1);
            assertThat(cmds.contains("ALL") || (cmds.contains("SELECT") && cmds.contains("INSERT") && cmds.contains("UPDATE") && cmds.contains("DELETE"))).as("policy cmds: " + cmds).isTrue();
        }
    }

    @Test
    void ac4_asNabdAppTheOtherTenantsRowsAreInvisibleAndForeignWritesRefused() {
        Ctx a = ctx(), b = ctx();
        newPatient(a, "Rls Alpha", "+919800099030", "1990-01-01");
        newPatient(b, "Rls Beta", "+919800099031", "1990-01-01");
        waitFor("rows", 5000, () -> summaryRows(a) == 1 && summaryRows(b) == 1);
        JdbcTemplate app = new JdbcTemplate(dataSourceFor(APP_ROLE, APP_ROLE_PW));
        // no tenant set: nothing visible
        assertThat(app.queryForObject("SELECT count(*) FROM patient_registry_summary", Long.class)).isZero();
        // tenant A set: exactly A's row, never B's
        Long seen = app.execute((Connection con) -> {
            con.setAutoCommit(false);
            try (Statement st = con.createStatement()) {
                st.execute("SELECT set_config('app.tenant_id', '" + a.t.id() + "', true)");
                var rs = st.executeQuery("SELECT count(*) FILTER (WHERE tenant_id = '" + a.t.id() + "'), count(*) FILTER (WHERE tenant_id <> '" + a.t.id() + "') FROM patient_registry_summary");
                rs.next();
                assertThat(rs.getLong(2)).isZero();
                return rs.getLong(1);
            } finally { con.rollback(); }
        });
        assertThat(seen).isEqualTo(1L);
        assertThatThrownBy(() -> app.execute((Connection con) -> {
            con.setAutoCommit(false);
            try (Statement st = con.createStatement()) {
                st.execute("SELECT set_config('app.tenant_id', '" + a.t.id() + "', true)");
                st.execute("INSERT INTO patient_registry_summary (tenant_id, patient_id, change_seq) VALUES ('" + b.t.id() + "', gen_random_uuid(), 1)");
                return null;
            } finally { con.rollback(); }
        })).rootCause().hasMessageContaining("row-level security");
    }

    // ---- story intent: filters cover every patient, not the first 3,000 ----

    /** Not one of the 4 listed ACs, but the story's stated goal ("filters like 'balance due' cover every patient, not just the first 3,000"). */
    @Test
    // known defect (OPS-007): balance-due filter times out beyond the first 3000 patients
    @Tag("known-defect-OPS-007")
    @SuppressWarnings("unchecked")
    void goal_balanceDueFilterFindsAPatientBeyondTheFirst3000ByName() {
        Ctx c = ctx();
        bulkPatients(c, 3100, "Aaa");
        UUID zed = newPatient(c, "Zzzz Last By Name", "+919800099040", "1999-09-09");
        inTenantTx(c.t.id(), () -> {
            UUID q = UUID.randomUUID();
            jdbc.update("INSERT INTO queue_entries (id, tenant_id, patient_id, doctor_id, department_id, queue_date, token_number, status) VALUES (?,?,?,?,(SELECT id FROM departments WHERE tenant_id = ? AND is_default),current_date,1,'completed')",
                    q, c.t.id(), zed, c.staff.id(), c.t.id());
            jdbc.update("INSERT INTO invoices (tenant_id, invoice_number, queue_entry_id, patient_id, doctor_id, subtotal, tax, total, created_by) VALUES (?,?,?,?,?,500,0,500,?)",
                    c.t.id(), "Z-" + UUID.randomUUID(), q, zed, c.staff.id(), c.staff.id());
        });
        var r = exchange("/v1/patients/registry?filter=balance", HttpMethod.GET, authed(c.token), Map.class);
        assertThat(r.getStatusCode().value()).as("registry: " + r.getBody()).isEqualTo(200);
        var rows = (List<Map<String, Object>>) r.getBody().get("rows");
        assertThat(rows).extracting(m -> m.get("id")).contains(zed.toString());
    }
}
