package com.nabd.hms.patient;

import com.nabd.hms.common.AuditService;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Independent QA (S10) for OPS-004. Written from the story's acceptance criteria. */
class S10QaOps004LastVisitTest extends ApiTestBase {

    private static final Instant STAMP = Instant.parse("2026-10-05T11:00:00Z"); // stands in for the V43 migration time

    @Autowired AuditService auditService;
    @Autowired PatientRepository patientRepository;

    private record Ctx(SeededTenant t, SeededStaff staff, String token) { }

    private int seq = 0;

    private Ctx ctx() {
        SeededTenant t = seedTenant();
        int n = 7400 + (int) (Math.random() * 90000);
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "s10q4" + UUID.randomUUID() + "@a.com", "+91981" + (1000000 + n), false);
        return new Ctx(t, s, loginAndGetAccessToken(s));
    }

    private String patient(Ctx c, String name) {
        int n = ++seq + (int) (Math.random() * 8_000_000);
        var r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token, Map.of("name", name + " " + UUID.randomUUID().toString().substring(0, 6), "phone", "+91977" + (1000000 + n),
                "dob", "1985-0" + (1 + n % 9) + "-1" + (n % 9), "gender", "female")), Map.class);
        assertThat(r.getStatusCode().value()).as("register: " + r.getBody()).isEqualTo(201);
        return (String) r.getBody().get("id");
    }

    private UUID entry(Ctx c, String patient, String date, int token, String status, Instant updatedAt) {
        UUID id = UUID.randomUUID();
        inTenantTx(c.t.id(), () -> jdbc.update("INSERT INTO queue_entries (id, tenant_id, patient_id, doctor_id, queue_date, token_number, status, department_id, created_at, updated_at) " +
                        "VALUES (?,?,?::uuid,?,?::date,?,?,(SELECT id FROM departments WHERE tenant_id = ? AND is_default), ?::timestamptz, ?::timestamptz)",
                id, c.t.id(), patient, c.staff.id(), date, token, status, c.t.id(), date + "T08:00:00Z", updatedAt.toString()));
        return id;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> header(Ctx c, String patient) {
        return exchange("/v1/patients/" + patient, HttpMethod.GET, authed(c.token), Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> registryRow(Ctx c, String patient) {
        var rows = (List<Map<String, Object>>) exchange("/v1/patients/registry", HttpMethod.GET, authed(c.token), Map.class).getBody().get("rows");
        return rows.stream().filter(r -> patient.equals(r.get("id"))).findFirst().orElseThrow();
    }

    // ---- AC1 ----

    @Test
    void ac1_headerAndRegistryShowTheCompletedVisitDate_notTheMigrationStamp() {
        Ctx c = ctx();
        String p = patient(c, "Returning");
        entry(c, p, "2026-03-02", 1, "completed", STAMP);
        assertThat(header(c, p)).containsEntry("lastVisit", "2026-03-02");
        assertThat((String) header(c, p).get("lastVisitAt")).startsWith("2026-03-02");
        assertThat(registryRow(c, p)).containsEntry("lastVisit", "2026-03-02");
    }

    @Test
    void ac1_latestCompletedWinsEvenWhenAnOlderVisitHasTheNewerUpdatedAt() {
        Ctx c = ctx();
        String p = patient(c, "Order");
        entry(c, p, "2026-03-02", 1, "completed", Instant.parse("2026-10-09T00:00:00Z")); // newest updated_at, older visit
        entry(c, p, "2026-05-20", 2, "completed", Instant.parse("2026-01-01T00:00:00Z")); // oldest updated_at, newer visit
        assertThat(header(c, p)).containsEntry("lastVisit", "2026-05-20");
        assertThat(registryRow(c, p)).containsEntry("lastVisit", "2026-05-20");
    }

    @Test
    void ac1_oneEntryPerDayBoundary_midnightCreatedAtStillShowsQueueDate() {
        Ctx c = ctx();
        String p = patient(c, "Midnight");
        entry(c, p, "2026-12-31", 1, "completed", STAMP);
        assertThat(header(c, p)).containsEntry("lastVisit", "2026-12-31");
    }

    // ---- AC2 ----

    @Test
    void ac2_noShowAndCancelledAppointmentsAreIgnored_noVisitAtAll() {
        Ctx c = ctx();
        String p = patient(c, "Ghost");
        entry(c, p, "2026-02-02", 1, "no_show", STAMP);
        try (Connection su = superuserConnection(); Statement st = su.createStatement()) {
            st.execute("INSERT INTO appointments (tenant_id, patient_id, doctor_id, start_time, end_time, status, cancel_reason) VALUES ('" + c.t.id() + "','" + p + "','" + c.staff.id()
                    + "', timestamptz '2026-02-10 10:00+00', timestamptz '2026-02-10 10:15+00', 'cancelled', 'patient asked')");
        } catch (Exception e) { throw new IllegalStateException(e); }
        Map<String, Object> h = header(c, p);
        assertThat(h.get("lastVisit")).isNull();
        assertThat(h.get("lastVisitAt")).isNull();
        assertThat(registryRow(c, p).get("lastVisit")).isNull();
    }

    @Test
    void ac2_noShowNewerThanACompletedVisitDoesNotReplaceIt() {
        Ctx c = ctx();
        String p = patient(c, "Mixed");
        entry(c, p, "2026-03-02", 1, "completed", STAMP);
        entry(c, p, "2026-04-01", 2, "no_show", STAMP);
        assertThat(header(c, p)).containsEntry("lastVisit", "2026-03-02");
        assertThat(registryRow(c, p)).containsEntry("lastVisit", "2026-03-02");
    }

    /** Story context: "derive last visit from queue_date ... of completed visits". A waiting/checked-in entry is not a completed visit. */
    @Test
    @Tag("known-defect-OPS-004")
    void ac2_unfinishedEntryNewerThanTheCompletedVisitIsNotALastVisit() {
        Ctx c = ctx();
        String p = patient(c, "Abandoned");
        entry(c, p, "2026-03-02", 1, "completed", STAMP);
        entry(c, p, "2026-04-01", 2, "checked_in", STAMP); // patient walked out before being seen; never completed
        assertThat(header(c, p)).containsEntry("lastVisit", "2026-03-02");
        assertThat(registryRow(c, p)).containsEntry("lastVisit", "2026-03-02");
    }

    @Test
    void ac2_inconsistentDefinitions_headerLastVisitAndLastVisitAtAgreeOnTheSameDay() {
        Ctx c = ctx();
        String p = patient(c, "Agree");
        entry(c, p, "2026-03-02", 1, "completed", STAMP);
        entry(c, p, "2026-04-01", 2, "in_consult", STAMP);
        Map<String, Object> h = header(c, p);
        assertThat(((String) h.get("lastVisitAt")).substring(0, 10)).isEqualTo(h.get("lastVisit"));
    }

    // ---- AC3 ----

    private LastVisitRepairJob job() {
        return new LastVisitRepairJob(jdbc, new TransactionTemplate(txManager), tenantContext, auditService, STAMP.toString());
    }

    private Map<String, Object> rowMinusUpdatedAt(Ctx c, UUID id) {
        return inTenantTx(c.t.id(), () -> jdbc.queryForMap("SELECT to_jsonb(q) - 'updated_at' AS j FROM queue_entries q WHERE id = ?", id));
    }

    private Instant updatedAt(Ctx c, UUID id) {
        return inTenantTx(c.t.id(), () -> jdbc.queryForObject("SELECT updated_at FROM queue_entries WHERE id = ?", java.sql.Timestamp.class, id)).toInstant();
    }

    private int auditRows(Ctx c) {
        return inTenantTx(c.t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'maintenance.last_visit_repair'", Integer.class));
    }

    @Test
    void ac3_repairWritesExactlyOneAuditRowWithCounts_andChangesNothingButUpdatedAt() {
        Ctx c = ctx();
        String p = patient(c, "Repair");
        List<UUID> stamped = new ArrayList<>();
        for (int i = 1; i <= 3; i++) stamped.add(entry(c, p, "2026-02-0" + i, i, i == 2 ? "no_show" : "completed", STAMP));
        UUID genuine = entry(c, p, "2026-03-04", 4, "completed", Instant.parse("2026-03-04T10:30:00Z"));
        List<Map<String, Object>> before = stamped.stream().map(id -> rowMinusUpdatedAt(c, id)).toList();
        Map<String, Object> genuineBefore = inTenantTx(c.t.id(), () -> jdbc.queryForMap("SELECT to_jsonb(q) AS j FROM queue_entries q WHERE id = ?", genuine));

        int restored = job().run(c.t.id(), List.of(STAMP));

        assertThat(restored).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            assertThat(rowMinusUpdatedAt(c, stamped.get(i))).as("row %d other columns", i).isEqualTo(before.get(i));
            assertThat(updatedAt(c, stamped.get(i))).isEqualTo(Instant.parse("2026-02-0" + (i + 1) + "T08:00:00Z")); // = created_at
        }
        assertThat(inTenantTx(c.t.id(), () -> jdbc.queryForMap("SELECT to_jsonb(q) AS j FROM queue_entries q WHERE id = ?", genuine))).isEqualTo(genuineBefore);
        assertThat(auditRows(c)).isEqualTo(1);
        Map<String, Object> a = inTenantTx(c.t.id(), () -> jdbc.queryForMap(
                "SELECT action, entity_type, after::text AS after, before::text AS before FROM audit_log WHERE action = 'maintenance.last_visit_repair'"));
        assertThat((String) a.get("after")).contains("\"rowsRestored\": 3");
        assertThat((String) a.get("after")).doesNotContain(p); // no patient id in the audit payload
    }

    @Test
    void ac3_runTwiceWritesOneAuditRowPerRun_secondRestoresZero() {
        Ctx c = ctx();
        String p = patient(c, "Twice");
        entry(c, p, "2026-02-01", 1, "completed", STAMP);
        assertThat(job().run(c.t.id(), List.of(STAMP))).isEqualTo(1);
        assertThat(auditRows(c)).isEqualTo(1);
        assertThat(job().run(c.t.id(), List.of(STAMP))).isZero();
        assertThat(auditRows(c)).isEqualTo(2);
    }

    @Test
    void ac3_repairIsTenantScoped_andRefusesAnEmptyStampList() {
        Ctx a = ctx(), b = ctx();
        String pa = patient(a, "A"), pb = patient(b, "B");
        UUID ea = entry(a, pa, "2026-02-01", 1, "completed", STAMP);
        UUID eb = entry(b, pb, "2026-02-01", 1, "completed", STAMP);
        job().run(a.t.id(), List.of(STAMP));
        assertThat(updatedAt(a, ea)).isEqualTo(Instant.parse("2026-02-01T08:00:00Z"));
        assertThat(updatedAt(b, eb)).isEqualTo(STAMP);
        assertThat(auditRows(b)).isZero();
        assertThatThrownBy(() -> job().run(a.t.id(), List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ac3_stampBoundaryOnlyExactStampMatches_oneSecondOffIsLeftAlone() {
        Ctx c = ctx();
        String p = patient(c, "Boundary");
        UUID near = entry(c, p, "2026-02-01", 1, "completed", STAMP.plusSeconds(1));
        UUID exact = entry(c, p, "2026-02-02", 2, "completed", STAMP);
        assertThat(job().run(c.t.id(), List.of(STAMP))).isEqualTo(1);
        assertThat(updatedAt(c, near)).isEqualTo(STAMP.plusSeconds(1));
        assertThat(updatedAt(c, exact)).isEqualTo(Instant.parse("2026-02-02T08:00:00Z"));
    }

    @Test
    void ac3_batchesPast10kRows_andTheRepairDoesNotRestampThemWithNow() {
        Ctx c = ctx();
        String p = patient(c, "Bulk");
        inTenantTx(c.t.id(), () -> jdbc.update("""
                INSERT INTO queue_entries (tenant_id, patient_id, doctor_id, department_id, queue_date, token_number, status, created_at, updated_at)
                SELECT ?, ?::uuid, ?, (SELECT id FROM departments WHERE tenant_id = ? AND is_default), date '2020-01-01' + (n / 200), (n % 200) + 1, 'completed',
                       timestamptz '2020-01-01 08:00+00', ?::timestamptz FROM generate_series(1, 10050) n""",
                c.t.id(), p, c.staff.id(), c.t.id(), STAMP.toString()));
        assertThat(job().run(c.t.id(), List.of(STAMP))).isEqualTo(10050);
        assertThat(inTenantTx(c.t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM queue_entries WHERE updated_at <> created_at", Integer.class))).isZero();
        Map<String, Object> a = inTenantTx(c.t.id(), () -> jdbc.queryForMap("SELECT after::text AS after FROM audit_log WHERE action = 'maintenance.last_visit_repair'"));
        assertThat((String) a.get("after")).contains("\"rowsRestored\": 10050").contains("\"batches\": 2");
    }

    // ---- AC4 ----

    private Instant tsOf(Connection c, UUID id) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT updated_at FROM queue_entries WHERE id = '" + id + "'")) {
            rs.next();
            return rs.getTimestamp(1).toInstant();
        }
    }

    @Test
    void ac4_backfillWithTheFlagLeavesUpdatedAtAlone_otherwiseTheTriggerStillFires() throws Exception {
        Ctx c = ctx();
        String p = patient(c, "Backfill");
        Instant old = Instant.parse("2026-03-02T08:00:00Z");
        UUID withFlag = entry(c, p, "2026-03-02", 1, "completed", old);
        UUID flagOff = entry(c, p, "2026-03-03", 2, "completed", old);
        UUID plain = entry(c, p, "2026-03-04", 3, "completed", old);
        UUID after = entry(c, p, "2026-03-05", 4, "completed", old);
        try (Connection su = superuserConnection()) {
            su.setAutoCommit(false);
            try (Statement st = su.createStatement()) {
                st.execute("SELECT set_config('app.tenant_id', '" + c.t.id() + "', true)");
                st.execute("SET LOCAL nabd.skip_updated_at = 'on'");
                st.execute("UPDATE queue_entries SET priority = false WHERE id = '" + withFlag + "'");
                st.execute("SET LOCAL nabd.skip_updated_at = 'off'");
                st.execute("UPDATE queue_entries SET priority = false WHERE id = '" + flagOff + "'");
            }
            su.commit();
            try (Statement st = su.createStatement()) { // a fresh transaction: the flag must not leak
                st.execute("UPDATE queue_entries SET priority = false WHERE id = '" + plain + "'");
            }
            su.commit();
            assertThat(tsOf(su, withFlag)).isEqualTo(old);
            assertThat(tsOf(su, flagOff)).isAfter(old);
            assertThat(tsOf(su, plain)).isAfter(old);
            assertThat(tsOf(su, after)).isEqualTo(old);
        }
    }

    @Test
    void ac4_flagIsHonouredOnOtherTablesUsingSetUpdatedAt() throws Exception {
        Ctx c = ctx();
        String p = patient(c, "Patients table");
        try (Connection su = superuserConnection()) {
            su.setAutoCommit(false);
            Instant before;
            try (Statement st = su.createStatement(); ResultSet rs = st.executeQuery("SELECT updated_at FROM patients WHERE id = '" + p + "'")) {
                rs.next();
                before = rs.getTimestamp(1).toInstant();
            }
            try (Statement st = su.createStatement()) {
                st.execute("SET LOCAL nabd.skip_updated_at = 'on'");
                st.execute("UPDATE patients SET address = 'x' WHERE id = '" + p + "'");
            }
            su.commit();
            try (Statement st = su.createStatement(); ResultSet rs = st.executeQuery("SELECT updated_at FROM patients WHERE id = '" + p + "'")) {
                rs.next();
                assertThat(rs.getTimestamp(1).toInstant()).isEqualTo(before);
            }
        }
    }

    @Test
    void ac4_playbookStatesTheRuleAndTheFlag() throws Exception {
        String pb = java.nio.file.Files.readString(java.nio.file.Path.of("../docs/architecture/migration-playbook.md"));
        assertThat(pb).contains("nabd.skip_updated_at");
        assertThat(pb.toLowerCase()).contains("backfill");
    }

    // ---- NFR ----

    @Test
    void nfr_findLastVisitAtUsesTheCompositePatientIndex_andIsFast() throws Exception {
        Ctx c = ctx();
        String p = patient(c, "Indexed");
        // 20k other rows so the planner has a reason to use the index
        inTenantTx(c.t.id(), () -> jdbc.update("""
                INSERT INTO queue_entries (tenant_id, patient_id, doctor_id, department_id, queue_date, token_number, status)
                SELECT ?, ?::uuid, ?, (SELECT id FROM departments WHERE tenant_id = ? AND is_default), date '2000-01-01' + (n / 100), (n % 100) + 1, 'completed' FROM generate_series(1, 20000) n""",
                c.t.id(), p, c.staff.id(), c.t.id()));
        String other = patient(c, "Other");
        entry(c, other, "2026-03-02", 1, "completed", STAMP);
        try (Connection su = superuserConnection(); Statement st = su.createStatement()) {
            st.execute("ANALYZE queue_entries");
        }
        try (Connection su = superuserConnection()) {
            su.setAutoCommit(false);
            try (Statement st = su.createStatement()) {
                st.execute("SET LOCAL jit = off");
                try (ResultSet rs = st.executeQuery("EXPLAIN SELECT max(queue_date) FROM queue_entries WHERE tenant_id = '" + c.t.id() + "' AND patient_id = '" + other + "' AND status = 'completed'")) {
                    StringBuilder plan = new StringBuilder();
                    while (rs.next()) plan.append(rs.getString(1)).append('\n');
                    assertThat(plan.toString()).contains("idx_queue_entries_patient");
                }
            }
            su.rollback();
        }
        List<Long> nanos = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            long t0 = System.nanoTime();
            inTenantTx(c.t.id(), () -> patientRepository.findLastVisitAt(c.t.id(), UUID.fromString(other)));
            nanos.add(System.nanoTime() - t0);
        }
        Collections.sort(nanos);
        double p95ms = nanos.get((int) (nanos.size() * 0.95)) / 1e6;
        System.out.println("OPS-004 findLastVisitAt p95 incl. tx+tenant setup = " + p95ms + " ms");
        assertThat(p95ms).as("p95 ms (includes a tenant transaction round trips)").isLessThan(5.0);
    }
}
