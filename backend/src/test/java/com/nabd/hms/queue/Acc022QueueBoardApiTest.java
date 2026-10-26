package com.nabd.hms.queue;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-022: board projection, batch patient read, reassign (dual lock + CAS), OPD-only filter, tenant isolation. */
class Acc022QueueBoardApiTest extends ApiTestBase {

    @Autowired com.nabd.hms.common.ClinicClock clock;

    SeededTenant tenant;
    SeededStaff drA;
    SeededStaff drB;
    String token;
    int n = 0;

    SeededStaff staff(SeededTenant t, UUID role, String tag) {
        return seedStaff(t, role, tag + UUID.randomUUID().toString().substring(0, 6) + "@a.com", "+9196" + (10000000 + (int) (Math.random() * 8999999)), false);
    }

    @BeforeEach
    void seed() {
        tenant = seedTenant();
        UUID role = seedFullAccessRole(tenant.id());
        drA = staff(tenant, role, "a");
        drB = staff(tenant, role, "b");
        token = loginAndGetAccessToken(drA);
    }

    String patient(String name) {
        int i = n++;
        return (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", "+91977" + (100000 + (int) (Math.random() * 899999)) + "0", "dob", (1950 + i) + "-01-01", "gender", "male")), Map.class).getBody().get("id");
    }

    String checkIn(SeededStaff doc, String name) {
        ResponseEntity<Map> c = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patient(name), "doctorId", doc.id().toString())), Map.class);
        assertThat(c.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) c.getBody().get("id");
    }

    ResponseEntity<Map> patch(String id, String status, String expected) {
        return exchange("/v1/queue/" + id + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", status, "expectedStatus", expected)), Map.class);
    }

    ResponseEntity<Map> reassign(String id, UUID to, String expected) {
        return exchange("/v1/queue/" + id + "/reassign", HttpMethod.POST, authedJsonBody(token, Map.of(
                "toDoctorId", to.toString(), "expectedStatus", expected, "reasonCode", "balance_load")), Map.class);
    }

    Map board() {
        return exchange("/v1/queue/board", HttpMethod.GET, authed(token), Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    Map doctor(Map board, UUID id) {
        return ((List<Map>) board.get("doctors")).stream().filter(d -> id.toString().equals(d.get("doctorId"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    List<Map> entries(Map board, UUID id) {
        return (List<Map>) doctor(board, id).get("entries");
    }

    @Test
    void boardListsEveryDoctorsQueueWithShortNamesPositionsAndNoPhoneOrDob() {
        String a1 = checkIn(drA, "Anita Rao");
        checkIn(drA, "Bilal Khan");
        checkIn(drB, "Chitra Menon Iyer");
        assertThat(patch(a1, "waiting", "checked_in").getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> raw = exchange("/v1/queue/board", HttpMethod.GET, authed(token), String.class);
        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(raw.getBody()).doesNotContain("+91").doesNotContain("1950-").doesNotContain("Rao").doesNotContain("Menon");
        Map b = board();
        assertThat(b.get("v")).isNotNull();
        assertThat(entries(b, drA.id())).extracting(e -> e.get("displayName")).containsExactly("Anita R.", "Bilal K.");
        assertThat(entries(b, drA.id())).extracting(e -> e.get("token")).containsExactly(1, 2);
        assertThat(entries(b, drA.id())).extracting(e -> e.get("position")).containsExactly(1, 2);
        assertThat(entries(b, drB.id()).get(0)).containsEntry("displayName", "Chitra I.").containsEntry("stage", "checked_in");
        assertThat(entries(b, drB.id()).get(0).get("mrn")).isNotNull();
        assertThat(doctor(b, drA.id())).containsEntry("state", "out");
        // a doctor in consult is "now serving" and has no position
        patch(a1, "vitals_pending", "waiting");
        patch(a1, "vitals_done", "vitals_pending");
        assertThat(patch(a1, "in_consult", "vitals_done").getStatusCode()).isEqualTo(HttpStatus.OK);
        Map b2 = board();
        assertThat(doctor(b2, drA.id())).containsEntry("nowServing", 1);
        assertThat(entries(b2, drA.id()).get(0)).containsEntry("position", 0);
        assertThat(doctor(b2, drA.id()).get("state")).isEqualTo("in"); // starting a consult opens the session (ACC-015)
    }

    @Test
    void inpatientAndOtherNonOpdEntriesNeverAppearOnTheBoardOrTheQueue() {
        String opd = checkIn(drA, "Opd One");
        String ipd = checkIn(drA, "Ipd Two");
        new JdbcTemplate(superuserDataSource()).update("UPDATE queue_entries SET class = 'inpatient' WHERE id = ?", UUID.fromString(ipd));
        assertThat(entries(board(), drA.id())).extracting(e -> e.get("id")).containsExactly(opd);
        List<Map> list = exchange("/v1/queue?doctorId=" + drA.id(), HttpMethod.GET, authed(token), List.class).getBody();
        assertThat(list).extracting(e -> e.get("id")).containsExactly(opd);
    }

    @Test
    void reassignMovesTheWaitingPatientGivesTheNextTokenAndKeepsTheOldOne() {
        checkIn(drB, "Bee One");
        checkIn(drB, "Bee Two");
        String e = checkIn(drA, "Aye One");
        String t9 = checkIn(drA, "Aye Two"); // token 2 of A
        patch(t9, "waiting", "checked_in");
        ResponseEntity<Map> r = reassign(t9, drB.id(), "waiting");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("previousToken", 2);
        Map entry = (Map) r.getBody().get("entry");
        assertThat(entry).containsEntry("doctorId", drB.id().toString()).containsEntry("tokenNumber", 3).containsEntry("status", "waiting");
        Map b = board();
        assertThat(entries(b, drB.id())).extracting(x -> x.get("token")).containsExactly(1, 2, 3);
        Map moved = entries(b, drB.id()).get(2);
        assertThat(moved).containsEntry("previousToken", 2);
        assertThat((List<String>) moved.get("flags")).contains("reassigned");
        assertThat(entries(b, drA.id())).extracting(x -> x.get("id")).containsExactly(e);
        // audit, queue event and the changed-event carry the move
        assertThat(inTenantTx(tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'queue.reassign' AND entity_id = ?", Integer.class, UUID.fromString(t9)))).isEqualTo(1);
        assertThat(inTenantTx(tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM queue_entry_events WHERE kind = 'reassign' AND queue_entry_id = ?", Integer.class, UUID.fromString(t9)))).isEqualTo(1);
        String payload = new JdbcTemplate(superuserDataSource()).queryForObject(
                "SELECT payload::text FROM outbox_events WHERE event_type = 'visit.queue_entry.changed.v1' AND payload::text LIKE '%newToken%' LIMIT 1", String.class);
        assertThat(payload).contains("fromDoctorId").contains("previousToken");
    }

    @Test
    void reassignRefusalsStaleDifferentDepartmentSameDoctorAndUnknown() {
        String id = checkIn(drA, "Stale One");
        patch(id, "waiting", "checked_in");
        patch(id, "vitals_pending", "waiting");
        patch(id, "vitals_done", "vitals_pending");
        patch(id, "in_consult", "vitals_done");
        ResponseEntity<Map> inConsult = reassign(id, drB.id(), "in_consult");
        assertThat(inConsult.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(inConsult.getBody()).containsEntry("code", "STALE_STATUS").containsEntry("currentStatus", "in_consult");

        String w = checkIn(drA, "Stale Two");
        ResponseEntity<Map> wrongExpected = reassign(w, drB.id(), "waiting"); // it is checked_in
        assertThat(wrongExpected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(wrongExpected.getBody()).containsEntry("currentStatus", "checked_in");
        assertThat(reassign(w, drA.id(), "checked_in").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reassign(w, UUID.randomUUID(), "checked_in").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(reassign(UUID.randomUUID().toString(), drB.id(), "checked_in").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        UUID derm = inTenantTx(tenant.id(), () -> jdbc.queryForObject("INSERT INTO departments (tenant_id, name) VALUES (?, 'Derm') RETURNING id", UUID.class, tenant.id()));
        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE staff SET department_id = ? WHERE id = ?", derm, drB.id()));
        ResponseEntity<Map> other = reassign(w, drB.id(), "checked_in");
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(other.getBody()).containsEntry("code", "DIFFERENT_DEPARTMENT");
        // nothing moved
        assertThat(entries(board(), drA.id())).extracting(x -> x.get("id")).contains(w);
    }

    @Test
    void reassignIntoAFullSessionIs409SessionFull() {
        int dow = clock.today(tenant.id()).getDayOfWeek().getValue() % 7;
        exchange("/v1/doctors/" + drB.id() + "/working-hours", HttpMethod.POST, authedJsonBody(token, Map.of(
                "dayOfWeek", dow, "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15, "maxPatients", 1)), Map.class);
        checkIn(drB, "Fills It"); // walk-in counted against the cap
        String id = checkIn(drA, "Wants In");
        ResponseEntity<Map> r = reassign(id, drB.id(), "checked_in");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).containsEntry("code", "SESSION_FULL");
    }

    @Test
    void reassignRacingAStatusMoveNeverLosesOrDuplicatesTheEntry() throws Exception {
        String id = checkIn(drA, "Race One");
        patch(id, "waiting", "checked_in");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<Integer> move = () -> { go.await(); return reassign(id, drB.id(), "waiting").getStatusCode().value(); };
        Callable<Integer> callNext = () -> { go.await(); return patch(id, "vitals_pending", "waiting").getStatusCode().value(); };
        Future<Integer> f1 = pool.submit(move);
        Future<Integer> f2 = pool.submit(callNext);
        go.countDown();
        int a = f1.get();
        int b = f2.get();
        pool.shutdown();
        // reassign is only legal while status is still 'waiting': both can win (move first, then the status move on B's copy) or one gets 409, never a 5xx
        assertThat(List.of(a, b)).allMatch(c -> c == 200 || c == 409);
        Map row = inTenantTx(tenant.id(), () -> jdbc.queryForMap("SELECT doctor_id, token_number, status FROM queue_entries WHERE id = ?", UUID.fromString(id)));
        int total = inTenantTx(tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM queue_entries WHERE id = ?", Integer.class, UUID.fromString(id)));
        assertThat(total).isEqualTo(1);
        if (a == 200) {
            assertThat(row.get("doctor_id")).isEqualTo(drB.id());
        } else {
            assertThat(row.get("doctor_id")).isEqualTo(drA.id());
        }
    }

    @Test
    void aPriorityFlagWithoutAReasonIs400ReasonRequired() {
        String id = checkIn(drA, "Pri One");
        ResponseEntity<Map> r = exchange("/v1/queue/" + id + "/reorder", HttpMethod.POST, authedJsonBody(token, Map.of("priority", true)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).containsEntry("code", "REASON_REQUIRED");
        assertThat(exchange("/v1/queue/" + id + "/reorder", HttpMethod.POST, authedJsonBody(token, Map.of("priority", true, "reason", "  ")), Map.class).getBody()).containsEntry("code", "REASON_REQUIRED");
        assertThat(exchange("/v1/queue/" + id + "/reorder", HttpMethod.POST, authedJsonBody(token, Map.of("priority", true, "reason", "elderly")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void batchPatientReadMax100SilentlyDropsOtherTenantsAndWritesPhiReadRows() {
        String p1 = patient("Batch Alpha");
        String p2 = patient("Batch Beta");
        SeededTenant other = seedTenant();
        SeededStaff otherStaff = staff(other, seedFullAccessRole(other.id()), "o");
        String otherToken = loginAndGetAccessToken(otherStaff);
        String foreign = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(otherToken, Map.of(
                "name", "Foreign Person", "phone", "+919811122233", "dob", "1980-01-01", "gender", "male")), Map.class).getBody().get("id");

        String rid = UUID.randomUUID().toString();
        var h = new org.springframework.http.HttpHeaders();
        h.putAll(authed(token).getHeaders());
        h.set("X-Request-Id", rid);
        ResponseEntity<Map> r = exchange("/v1/patients?ids=" + p1 + "," + p2 + "," + foreign + "," + UUID.randomUUID(), HttpMethod.GET, new org.springframework.http.HttpEntity<>(h), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map> items = (List<Map>) r.getBody().get("items");
        assertThat(items).extracting(x -> x.get("id")).containsExactlyInAnyOrder(p1, p2);
        assertThat(items).extracting(x -> x.get("displayName")).containsExactlyInAnyOrder("Batch A.", "Batch B.");
        assertThat(items.get(0)).doesNotContainKeys("phone", "dob", "name");

        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 101; i++) ids.add(UUID.randomUUID().toString());
        ResponseEntity<Map> many = exchange("/v1/patients?ids=" + String.join(",", ids), HttpMethod.GET, authed(token), Map.class);
        assertThat(many.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(many.getBody()).containsEntry("code", "TOO_MANY_IDS");
        ids.remove(0);
        assertThat(exchange("/v1/patients?ids=" + String.join(",", Collections.nCopies(1, ids.get(0))), HttpMethod.GET, authed(token), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        // exactly 100 is fine
        assertThat(exchange("/v1/patients?ids=" + String.join(",", ids), HttpMethod.GET, authed(token), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anotherTenantsBoardIsEmptyOfThisClinic() {
        checkIn(drA, "Mine Only");
        SeededTenant other = seedTenant();
        SeededStaff o = staff(other, seedFullAccessRole(other.id()), "z");
        Map b = exchange("/v1/queue/board", HttpMethod.GET, authed(loginAndGetAccessToken(o)), Map.class).getBody();
        assertThat(((List<?>) b.get("doctors")).stream().map(String::valueOf).toList()).noneMatch(s -> s.contains(drA.id().toString()));
    }

    @Test
    void aClinicWith10DoctorsAnd120EntriesLoadsTheBoardFast() {
        UUID role = seedRole(tenant.id(), "Doc", false, new ModuleGrant("queue", true, true, true, false, false, false, false));
        List<SeededStaff> docs = new ArrayList<>(List.of(drA, drB));
        for (int i = 2; i < 10; i++) docs.add(staff(tenant, role, "m" + i));
        var day = clock.today(tenant.id());
        UUID dept = inTenantTx(tenant.id(), () -> jdbc.queryForObject("SELECT id FROM departments WHERE tenant_id = ? AND is_default", UUID.class, tenant.id()));
        inTenantTx(tenant.id(), () -> {
            for (int i = 0; i < 120; i++) {
                UUID p = jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,?,?,'1990-01-01','other') RETURNING id", UUID.class,
                        tenant.id(), "Perf Patient" + i, "+9188" + String.format("%08d", i));
                jdbc.update("INSERT INTO queue_entries (id, tenant_id, patient_id, doctor_id, department_id, queue_date, token_number, source, status) VALUES (gen_random_uuid(),?,?,?,?,?,?,'walk_in','waiting')",
                        tenant.id(), p, docs.get(i % 10).id(), dept, Date.valueOf(day), i / 10 + 1);
            }
        });
        for (int i = 0; i < 5; i++) board(); // warm up
        List<Long> ms = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long t0 = System.nanoTime();
            Map b = board();
            ms.add((System.nanoTime() - t0) / 1_000_000);
            assertThat(((List<?>) b.get("doctors"))).hasSize(10);
        }
        Collections.sort(ms);
        long p95 = ms.get(18);
        assertThat(p95).as("board p95 ms over 20 calls, 10 doctors, 120 entries: " + ms).isLessThan(200);
    }
}
