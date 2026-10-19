package com.nabd.hms.queue;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-002: queue moves are compare-and-set; completion is billing-only; no_show only before in_consult. */
class QueueCasApiTest extends ApiTestBase {

    private String token;
    private SeededTenant tenant;
    private SeededStaff staff;
    private int n = 0;

    private void setUp(String email, String phone) {
        tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        staff = seedStaff(tenant, roleId, email, phone, false);
        token = loginAndGetAccessToken(staff);
    }

    private String checkIn() {
        int i = n++;
        ResponseEntity<Map> p = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Cas" + i, "phone", "+9199991" + String.format("%05d", i), "dob", (1950 + i) + "-01-01", "gender", "male")), Map.class);
        ResponseEntity<Map> c = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", p.getBody().get("id"), "doctorId", staff.id().toString())), Map.class);
        assertThat(c.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) c.getBody().get("id");
    }

    private ResponseEntity<Map> patch(String id, String status, String expected) {
        Map<String, String> body = expected == null ? Map.of("status", status) : Map.of("status", status, "expectedStatus", expected);
        return exchange("/v1/queue/" + id + "/status", HttpMethod.PATCH, authedJsonBody(token, body), Map.class);
    }

    private String dbStatus(String id) {
        return inTenantTx(tenant.id(), () -> jdbc.queryForObject("SELECT status FROM queue_entries WHERE id = ?", String.class, UUID.fromString(id)));
    }

    private void walk(String id, String... statuses) {
        String prev = dbStatus(id);
        for (String s : statuses) {
            assertThat(patch(id, s, prev).getStatusCode()).isEqualTo(HttpStatus.OK);
            prev = s;
        }
    }

    /** Fires `threads` identical moves at once; returns the status codes. */
    private List<HttpStatus> race(String id, int threads, String status, String expected) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<HttpStatus>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            fs.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return (HttpStatus) patch(id, status, expected).getStatusCode();
            }));
        }
        ready.await();
        go.countDown();
        List<HttpStatus> out = new ArrayList<>();
        for (Future<HttpStatus> f : fs) {
            out.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
        }
        pool.shutdown();
        return out;
    }

    @Test
    void twoScreensRacingTheSameMoveGiveOne200AndOne409WithCurrentStatus() throws Exception {
        setUp("cas1@a.com", "+919610000001");
        String id = checkIn();
        walk(id, "waiting");
        for (int threads : new int[]{2, 10}) {
            String from = dbStatus(id);
            String to = from.equals("waiting") ? "vitals_pending" : "vitals_done";
            List<HttpStatus> codes = race(id, threads, to, from);
            assertThat(codes.stream().filter(c -> c == HttpStatus.OK).count()).isEqualTo(1);
            assertThat(codes.stream().filter(c -> c == HttpStatus.CONFLICT).count()).isEqualTo(threads - 1);
            assertThat(dbStatus(id)).isEqualTo(to);
        }
        ResponseEntity<Map> stale = patch(id, "vitals_pending", "waiting");
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody().get("type")).asString().contains("stale-status");
        assertThat(stale.getBody().get("currentStatus")).isEqualTo("vitals_done");
    }

    @Test
    void completedViaPatchIs403AndOnlyCheckoutCompletes() {
        setUp("cas2@a.com", "+919610000002");
        String id = checkIn();
        walk(id, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        ResponseEntity<Map> r = patch(id, "completed", "checkout_pending");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody().get("type")).asString().contains("completion-reserved-for-billing");
        assertThat(dbStatus(id)).isEqualTo("checkout_pending");

        ResponseEntity<Map> co = exchange("/v1/billing/checkout/" + id, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("chargeCode", "X", "chargeName", "X", "category", "Service",
                        "quantity", 1, "unitPrice", 100, "taxRatePercent", 0)))), Map.class);
        assertThat(co.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dbStatus(id)).isEqualTo("completed");
        // the status change also reached the outbox in the same transaction
        Integer events = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'visit.queue_entry.status_changed.v1' AND payload->>'queueEntryId' = ? AND payload->>'toStatus' = 'completed'",
                Integer.class, id));
        assertThat(events).isEqualTo(1);
    }

    @Test
    void noShowIsIllegalFromInConsultButLegalBefore() {
        setUp("cas3@a.com", "+919610000003");
        String late = checkIn();
        walk(late, "waiting", "vitals_pending", "vitals_done", "in_consult");
        ResponseEntity<Map> r = patch(late, "no_show", "in_consult");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("type")).asString().contains("illegal-transition");
        assertThat(dbStatus(late)).isEqualTo("in_consult");

        String early = checkIn();
        walk(early, "waiting", "vitals_pending");
        assertThat(patch(early, "no_show", "vitals_pending").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void fiftyConcurrentTransitionsNeverSkipAStepAndStayFast() throws Exception {
        setUp("cas4@a.com", "+919610000004");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(checkIn());
        }
        List<String> sequence = List.of("checked_in", "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<Long>> fs = new ArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        for (String id : ids) {
            for (int k = 0; k < 10; k++) { // 10 callers per entry, each trying the move from the status they all saw
                fs.add(pool.submit(() -> {
                    go.await();
                    long t = System.nanoTime();
                    patch(id, "waiting", "checked_in");
                    return (System.nanoTime() - t) / 1_000_000;
                }));
            }
        }
        go.countDown();
        List<Long> ms = new ArrayList<>();
        for (Future<Long> f : fs) {
            ms.add(f.get(120, java.util.concurrent.TimeUnit.SECONDS));
        }
        pool.shutdown();
        java.util.Collections.sort(ms);
        // generous HTTP-level bound (includes JWT auth + two round trips); the CAS statement itself is one indexed UPDATE
        assertThat(ms.get((int) Math.ceil(ms.size() * 0.95) - 1)).isLessThan(2000L);
        for (String id : ids) {
            assertThat(sequence.indexOf(dbStatus(id))).isEqualTo(1); // exactly one hop: checked_in -> waiting, nothing skipped
        }
    }

    @Test
    void anOldClientWithoutExpectedStatusStillGetsTheServerSideCasAndLegalityChecks() {
        setUp("cas5@a.com", "+919610000005");
        String id = checkIn();
        assertThat(patch(id, "in_consult", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(patch(id, "waiting", null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
