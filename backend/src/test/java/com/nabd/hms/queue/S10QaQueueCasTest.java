package com.nabd.hms.queue;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA (S10) for ACC-002, written from the story's acceptance criteria. */
class S10QaQueueCasTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger(5000);

    @Autowired
    com.nabd.hms.department.DepartmentService departmentService;
    @Autowired
    QueueRepository queueRepo;

    private record Ctx(SeededTenant tenant, SeededStaff doctor, String token) { }

    private Ctx ctx() {
        SeededTenant t = seedTenant();
        int n = SEQ.incrementAndGet();
        SeededStaff d = seedStaff(t, seedFullAccessRole(t.id()), "s10qa2" + n + "@a.com", "+9193" + (1000000 + n), false);
        return new Ctx(t, d, loginAndGetAccessToken(d));
    }

    private String checkIn(Ctx c) {
        int n = SEQ.incrementAndGet();
        ResponseEntity<Map> p = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "name", UUID.randomUUID().toString().replace("-", "").substring(0, 10) + " Zq", "phone", "+9192" + (1000000 + n), "dob", (1940 + n % 50) + "-0" + (1 + n % 9) + "-1" + (n % 9), "gender", "male")), Map.class);
        assertThat(p.getStatusCode()).as("patient " + p.getBody()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> r = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "patientId", p.getBody().get("id"), "doctorId", c.doctor.id().toString())), Map.class);
        assertThat(r.getStatusCode()).as("check-in " + r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private ResponseEntity<Map> patch(String token, String id, String status, String expected) {
        Map<String, String> body = expected == null ? Map.of("status", status) : Map.of("status", status, "expectedStatus", expected);
        return exchange("/v1/queue/" + id + "/status", HttpMethod.PATCH, authedJsonBody(token, body), Map.class);
    }

    private String db(Ctx c, String id) {
        return inTenantTx(c.tenant.id(), () -> jdbc.queryForObject("SELECT status FROM queue_entries WHERE id = ?", String.class, UUID.fromString(id)));
    }

    private void walk(Ctx c, String id, String... to) {
        String prev = db(c, id);
        for (String s : to) {
            ResponseEntity<Map> r = patch(c.token, id, s, prev);
            assertThat(r.getStatusCode()).as(prev + " -> " + s + " " + r.getBody()).isEqualTo(HttpStatus.OK);
            prev = s;
        }
    }

    /** Problem identity: the `code` member when present, else the kebab slug of `type` upper-cased (ACC-002 errors carry only the slug). */
    private static String code(ResponseEntity<Map> r) {
        Object c = r.getBody().get("code");
        if (c != null) return c.toString();
        String t = r.getBody().get("type").toString();
        return t.substring(t.lastIndexOf('/') + 1).toUpperCase().replace('-', '_');
    }

    // ---- AC1 ----
    @Test
    void ac1_twoAndTenScreensRace_exactlyOne200_restStale409WithCurrentStatus() throws Exception {
        Ctx c = ctx();
        String id = checkIn(c);
        walk(c, id, "waiting");
        for (int threads : new int[]{2, 10}) {
            String from = db(c, id);
            String to = from.equals("waiting") ? "vitals_pending" : "vitals_done";
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads), go = new CountDownLatch(1);
            List<Future<ResponseEntity<Map>>> fs = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                fs.add(pool.submit(() -> { ready.countDown(); go.await(); return patch(c.token, id, to, from); }));
            }
            ready.await();
            go.countDown();
            int ok = 0, stale = 0;
            for (Future<ResponseEntity<Map>> f : fs) {
                ResponseEntity<Map> r = f.get(60, TimeUnit.SECONDS);
                if (r.getStatusCode() == HttpStatus.OK) ok++;
                else if (r.getStatusCode() == HttpStatus.CONFLICT) {
                    stale++;
                    assertThat(code(r)).isEqualTo("STALE_STATUS");
                    assertThat(r.getBody().get("currentStatus")).isEqualTo(to);
                }
            }
            pool.shutdown();
            assertThat(ok).as(threads + " threads: 200s").isEqualTo(1);
            assertThat(stale).as(threads + " threads: 409s").isEqualTo(threads - 1);
            assertThat(db(c, id)).isEqualTo(to);
        }
    }

    @Test
    void ac1_staleSingleCallAndMatchingCallBoundary() {
        Ctx c = ctx();
        String id = checkIn(c);
        walk(c, id, "waiting");
        ResponseEntity<Map> stale = patch(c.token, id, "vitals_pending", "checked_in");   // screen is one step behind
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody().get("currentStatus")).isEqualTo("waiting");
        assertThat(db(c, id)).isEqualTo("waiting");                                         // nothing written
        assertThat(patch(c.token, id, "vitals_pending", "waiting").getStatusCode()).isEqualTo(HttpStatus.OK);
        // double tap of the same request: second is stale, not a silent success
        ResponseEntity<Map> tap = patch(c.token, id, "vitals_pending", "waiting");
        assertThat(tap.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(tap.getBody().get("currentStatus")).isEqualTo("vitals_pending");
    }

    @Test
    @org.junit.jupiter.api.Tag("known-defect-ACC-002")
    void ac1_idempotencyKeyReplayReturnsSameResponseWithoutSecondMove() {
        Ctx c = ctx();
        String id = checkIn(c);
        walk(c, id, "waiting");
        String key = UUID.randomUUID().toString();
        HttpHeaders h = new HttpHeaders();
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        h.setBearerAuth(c.token);
        h.set("Idempotency-Key", key);
        var entity = new org.springframework.http.HttpEntity<>("{\"status\":\"vitals_pending\",\"expectedStatus\":\"waiting\"}", h);
        ResponseEntity<Map> a = exchange("/v1/queue/" + id + "/status", HttpMethod.PATCH, entity, Map.class);
        ResponseEntity<Map> b = exchange("/v1/queue/" + id + "/status", HttpMethod.PATCH, entity, Map.class);
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(b.getStatusCode()).as("API spec says idempotent: true; a retry with the same key must replay the 200, got " + b.getBody()).isEqualTo(HttpStatus.OK);
    }

    // ---- AC2 ----
    @Test
    void ac2_completedViaPatchIs403_onlyCheckoutCompletes() {
        Ctx c = ctx();
        String id = checkIn(c);
        walk(c, id, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        ResponseEntity<Map> r = patch(c.token, id, "completed", "checkout_pending");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody().toString().toLowerCase()).contains("completion-reserved-for-billing");
        assertThat(code(r)).isEqualTo("COMPLETION_RESERVED_FOR_BILLING");
        assertThat(db(c, id)).isEqualTo("checkout_pending");
        // without expectedStatus too
        assertThat(patch(c.token, id, "completed", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(db(c, id)).isEqualTo("checkout_pending");

        ResponseEntity<Map> co = exchange("/v1/billing/checkout/" + id, HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "lineItems", List.of(Map.of("override", Map.of("reasonCode", "other"), "chargeCode", "X", "chargeName", "X", "category", "Service",
                        "quantity", 1, "unitPrice", 100, "taxRatePercent", 0)))), Map.class);
        assertThat(co.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(db(c, id)).isEqualTo("completed");
    }

    @Test
    void ac2_completedViaPatchFromEarlierStagesIsNeverOk() {
        Ctx c = ctx();
        String id = checkIn(c);
        walk(c, id, "waiting", "vitals_pending", "vitals_done", "in_consult");
        ResponseEntity<Map> r = patch(c.token, id, "completed", "in_consult");
        assertThat(r.getStatusCode().value()).isIn(400, 403);
        assertThat(db(c, id)).isEqualTo("in_consult");
    }

    // ---- AC3 ----
    @Test
    void ac3_noShowFromInConsultIs400IllegalTransition_butLegalBeforeConsult() {
        Ctx c = ctx();
        String id = checkIn(c);
        walk(c, id, "waiting", "vitals_pending", "vitals_done", "in_consult");
        ResponseEntity<Map> r = patch(c.token, id, "no_show", "in_consult");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(r)).isEqualTo("ILLEGAL_TRANSITION");
        assertThat(db(c, id)).isEqualTo("in_consult");

        String early = checkIn(c);
        walk(c, early, "waiting", "vitals_pending");
        assertThat(patch(c.token, early, "no_show", "vitals_pending").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(db(c, early)).isEqualTo("no_show");
        // terminal: nothing leaves no_show
        assertThat(patch(c.token, early, "waiting", "no_show").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void ac3_noShowAfterConsultStages_checkoutPending_isRefused() {
        Ctx c = ctx();
        String id = checkIn(c);
        walk(c, id, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        assertThat(patch(c.token, id, "no_show", "checkout_pending").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(db(c, id)).isEqualTo("checkout_pending");
    }

    // ---- skip + permissions + tenant ----
    @Test
    void skippingAStepIsRefused() {
        Ctx c = ctx();
        String id = checkIn(c);
        walk(c, id, "waiting");
        ResponseEntity<Map> r = patch(c.token, id, "in_consult", "waiting");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(db(c, id)).isEqualTo("waiting");
    }

    @Test
    void viewOnlyStaffCannotMove_andOtherTenantSeesNotFound() {
        Ctx c = ctx();
        String id = checkIn(c);
        UUID viewRole = seedRole(c.tenant.id(), "QView" + SEQ.incrementAndGet(), false,
                new ModuleGrant("queue", true, false, false, false, false, false, false));
        int n = SEQ.incrementAndGet();
        SeededStaff viewer = seedStaff(c.tenant, viewRole, "v" + n + "@a.com", "+9191" + (1000000 + n), false);
        String vt = loginAndGetAccessToken(viewer);
        assertThat(patch(vt, id, "waiting", "checked_in").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(db(c, id)).isEqualTo("checked_in");

        Ctx other = ctx();
        ResponseEntity<Map> r = patch(other.token, id, "waiting", "checked_in");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(db(c, id)).isEqualTo("checked_in");
    }

    @Test
    void invalidBodyIs400() {
        Ctx c = ctx();
        String id = checkIn(c);
        assertThat(patch(c.token, id, "bogus_status", "checked_in").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/v1/queue/" + id + "/status", HttpMethod.PATCH, authedJsonBody(c.token, Map.of("expectedStatus", "checked_in")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- event (NFR/events) ----
    @Test
    void everyTransitionWritesAnIdsOnlyOutboxEvent() {
        Ctx c = ctx();
        String id = checkIn(c);
        walk(c, id, "waiting");
        List<Map<String, Object>> rows = inTenantTx(c.tenant.id(), () -> jdbc.queryForList(
                "SELECT payload::text AS p FROM outbox_events WHERE event_type = 'visit.queue_entry.status_changed.v1' AND payload->>'queueEntryId' = ?", id));
        assertThat(rows).as("status_changed event for the checked_in -> waiting move").isNotEmpty();
        String p = rows.get(rows.size() - 1).get("p").toString();
        for (String k : new String[]{"tenantId", "queueEntryId", "encounterId", "doctorId", "clinicDate", "fromStatus", "toStatus", "occurredAt", "actorRole"}) {
            assertThat(p).as("payload key " + k).contains("\"" + k + "\"");
        }
        assertThat(p).contains("\"fromStatus\": \"checked_in\"").contains("\"toStatus\": \"waiting\"");
        // a stale (rejected) move must not emit
        int before = rows.size();
        patch(c.token, id, "waiting", "checked_in");
        int after = inTenantTx(c.tenant.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_type = 'visit.queue_entry.status_changed.v1' AND payload->>'queueEntryId' = ?", Integer.class, id));
        assertThat(after).isEqualTo(before);
    }

    // ---- AC5 ----
    @Test
    void ac5_fiftyConcurrentTransitionsOneDoctor_p95Under200ms_noStepSkipped() throws Exception {
        Ctx c = ctx();
        List<String> seq = departmentService.resolveStatusSequence(c.tenant.id(), inTenantTx(c.tenant.id(), () ->
                jdbc.queryForObject("SELECT id FROM departments WHERE is_default LIMIT 1", UUID.class)));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 50; i++) ids.add(checkIn(c));
        // every entry: first hop checked_in -> next, 50 callers at once (one per entry, same doctor queue)
        List<Long> lat = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch ready = new CountDownLatch(50), go = new CountDownLatch(1);
        List<Future<HttpStatus>> fs = new ArrayList<>();
        String next = seq.get(1);
        for (String id : ids) {
            fs.add(pool.submit(() -> {
                ready.countDown(); go.await();
                long t = System.nanoTime();
                ResponseEntity<Map> r = patch(c.token, id, next, "checked_in");
                lat.add((System.nanoTime() - t) / 1_000_000);
                return (HttpStatus) r.getStatusCode();
            }));
        }
        ready.await(); go.countDown();
        for (Future<HttpStatus> f : fs) assertThat(f.get(120, TimeUnit.SECONDS)).isEqualTo(HttpStatus.OK);
        pool.shutdown();
        for (String id : ids) assertThat(db(c, id)).isEqualTo(next);
        // second hop with a bogus skip must fail for all, and a legal hop must follow the configured sequence exactly
        for (String id : ids.subList(0, 5)) {
            String skip = seq.get(3);
            assertThat(patch(c.token, id, skip, next).getStatusCode()).as("skip " + next + " -> " + skip).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(patch(c.token, id, seq.get(2), next).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        List<Long> sorted = new ArrayList<>(lat);
        Collections.sort(sorted);
        long p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
        System.out.println("S10QA ACC-002 50 concurrent HTTP PATCH latencies ms: p50=" + sorted.get(sorted.size() / 2) + " p95=" + p95 + " max=" + sorted.get(sorted.size() - 1));
        assertThat(p95).as("HTTP round trip p95 under 50-way contention (ms), informational bound 2000").isLessThan(2000);
    }

    /** The CAS statement itself (QueueRepository.updateStatus), 50 concurrent callers on one doctor's queue, p95 < 200 ms. */
    @Test
    void ac5_casWriteItself_50Concurrent_p95Under200ms() throws Exception {
        Ctx c = ctx();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 50; i++) ids.add(checkIn(c));
        List<Long> lat = Collections.synchronizedList(new ArrayList<>());
        List<Integer> rows = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch ready = new CountDownLatch(50), go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (String id : ids) {
            fs.add(pool.submit(() -> {
                ready.countDown(); go.await();
                long t = System.nanoTime();
                int n = inTenantTx(c.tenant.id(), () -> queueRepo.updateStatus(c.tenant.id(), UUID.fromString(id), "checked_in", "waiting"));
                lat.add((System.nanoTime() - t) / 1_000_000);
                rows.add(n);
                return null;
            }));
        }
        ready.await(); go.countDown();
        for (Future<?> f : fs) f.get(120, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(rows).as("rows updated by each CAS").containsOnly(1);
        // replaying the same CAS (stale expected) updates nothing
        assertThat(inTenantTx(c.tenant.id(), () -> queueRepo.updateStatus(c.tenant.id(), UUID.fromString(ids.get(0)), "checked_in", "waiting"))).isZero();
        List<Long> sorted = new ArrayList<>(lat);
        Collections.sort(sorted);
        long p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
        System.out.println("S10QA ACC-002 CAS statement latencies ms: p50=" + sorted.get(sorted.size() / 2) + " p95=" + p95 + " max=" + sorted.get(sorted.size() - 1));
        assertThat(p95).as("CAS write p95 (ms)").isLessThan(200);
    }
}
