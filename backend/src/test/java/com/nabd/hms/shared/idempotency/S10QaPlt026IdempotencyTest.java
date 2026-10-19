package com.nabd.hms.shared.idempotency;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA (S10) for PLT-026: Idempotency-Key. Written from the story's acceptance criteria. */
class S10QaPlt026IdempotencyTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 3_000_000));

    @Autowired IdempotencyStore store;
    @Autowired RequestMappingHandlerMapping mappings;

    private String phone() {
        return "+91982" + String.format("%07d", 1_000_000 + SEQ.incrementAndGet() % 8_000_000);
    }

    private record Ctx(SeededTenant tenant, SeededStaff staff, String token) { }

    private Ctx ctx() {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "q" + t.id() + "@a.com", phone(), false);
        return new Ctx(t, s, loginAndGetAccessToken(s));
    }

    private HttpEntity<String> withKey(String token, String key, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        if (key != null) h.set("Idempotency-Key", key);
        try {
            return new HttpEntity<>(objectMapper.writeValueAsString(body), h);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<Map> post(String path, String token, String key, Object body) {
        return exchange(path, HttpMethod.POST, withKey(token, key, body), Map.class);
    }

    private <T> List<T> parallel(int n, java.util.function.IntFunction<Callable<T>> work) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(n);
        CyclicBarrier gate = new CyclicBarrier(n);
        List<Future<T>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Callable<T> c = work.apply(i);
            fs.add(ex.submit(() -> { gate.await(); return c.call(); }));
        }
        List<T> out = new ArrayList<>();
        for (Future<T> f : fs) out.add(f.get());
        ex.shutdown();
        return out;
    }

    private void su(String sql, Object... args) {
        try (Connection c = superuserConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private int keyRows(UUID tenantId) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject("SELECT count(*) FROM idempotency_keys", Integer.class));
    }

    private int queueEntries(Ctx c) {
        return inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM queue_entries", Integer.class));
    }

    private String registerPatient(String token) {
        return (String) post("/v1/patients", token, UUID.randomUUID().toString(), Map.of(
                "name", "Qa " + UUID.randomUUID().toString().replace("-", "").substring(0, 12), "phone", phone(), "dob", "1990-01-01", "gender", "male")).getBody().get("id");
    }

    // AC1 (on a generic @Idempotent endpoint; the real payment endpoint is covered in S10QaFin001PaymentsTest)
    @Test
    void retryWithSameKeyReplaysStatusBodyAndHeaderAndRunsOnce() {
        Ctx c = ctx();
        String key = UUID.randomUUID().toString();
        int before = IdempotencyEchoController.CALLS.get();
        ResponseEntity<Map> first = post("/v1/test-idem/echo", c.token(), key, Map.of("a", 1));
        ResponseEntity<Map> r2 = post("/v1/test-idem/echo", c.token(), key, Map.of("a", 1));
        ResponseEntity<Map> r3 = post("/v1/test-idem/echo", c.token(), key, Map.of("a", 1));
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        for (ResponseEntity<Map> r : List.of(r2, r3)) {
            assertThat(r.getStatusCode()).isEqualTo(first.getStatusCode());
            assertThat(r.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
            assertThat(r.getBody()).isEqualTo(first.getBody());
        }
        assertThat(IdempotencyEchoController.CALLS.get() - before).isEqualTo(1);
    }

    // AC2
    @Test
    void twoIdenticalCheckInsWithTheSameKeyCreateOneQueueEntry() throws Exception {
        Ctx c = ctx();
        String patientId = registerPatient(c.token());
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("patientId", patientId, "doctorId", c.staff().id().toString());
        List<ResponseEntity<Map>> rs = parallel(2, i -> () -> post("/v1/queue/check-in", c.token(), key, body));
        assertThat(queueEntries(c)).isEqualTo(1);
        for (ResponseEntity<Map> r : rs) {
            boolean original = r.getStatusCode().is2xxSuccessful();
            boolean inProgress = r.getStatusCode() == HttpStatus.CONFLICT && "IDEMPOTENCY_IN_PROGRESS".equals(r.getBody().get("code"));
            assertThat(original || inProgress).as("status %s body %s", r.getStatusCode(), r.getBody()).isTrue();
        }
    }

    @Test
    void eightSimultaneousCheckInsWithOneKeyStillCreateOneEntryAndSequentialRetryReplays() throws Exception {
        Ctx c = ctx();
        String patientId = registerPatient(c.token());
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("patientId", patientId, "doctorId", c.staff().id().toString());
        List<ResponseEntity<Map>> rs = parallel(8, i -> () -> post("/v1/queue/check-in", c.token(), key, body));
        assertThat(queueEntries(c)).isEqualTo(1);
        long fresh = rs.stream().filter(r -> r.getStatusCode().is2xxSuccessful() && r.getHeaders().getFirst("Idempotent-Replayed") == null).count();
        assertThat(fresh).isEqualTo(1);
        ResponseEntity<Map> later = post("/v1/queue/check-in", c.token(), key, body);
        assertThat(later.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(later.getBody().get("tokenNumber")).isNotNull();
        assertThat(queueEntries(c)).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateWhileFirstIsRunningGets409ThenReplaysOnceFinished() throws Exception {
        Ctx c = ctx();
        String key = UUID.randomUUID().toString();
        int before = IdempotencyEchoController.CALLS.get();
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<ResponseEntity<Map>> slow = ex.submit(() -> post("/v1/test-idem/echo", c.token(), key, Map.of("sleepMs", 1500)));
        Thread.sleep(500);
        ResponseEntity<Map> dup = post("/v1/test-idem/echo", c.token(), key, Map.of("sleepMs", 1500));
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dup.getBody().get("code")).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
        assertThat(slow.get().getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> after = post("/v1/test-idem/echo", c.token(), key, Map.of("sleepMs", 1500));
        assertThat(after.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(IdempotencyEchoController.CALLS.get() - before).isEqualTo(1);
        ex.shutdown();
    }

    // AC3
    @Test
    void sameKeyWithDifferentBodyOrDifferentPathIs422KeyReused() {
        Ctx c = ctx();
        String key = UUID.randomUUID().toString();
        int before = IdempotencyEchoController.CALLS.get();
        assertThat(post("/v1/test-idem/echo", c.token(), key, Map.of("a", 1)).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> body2 = post("/v1/test-idem/echo", c.token(), key, Map.of("a", 2));
        assertThat(body2.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(body2.getBody().get("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(body2.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        // same key + same body on a different @Idempotent path is a different request too
        ResponseEntity<Map> path2 = post("/v1/queue/check-in", c.token(), key, Map.of("a", 1));
        assertThat(path2.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(path2.getBody().get("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(IdempotencyEchoController.CALLS.get() - before).isEqualTo(1);
        // the original is untouched and still replays
        assertThat(post("/v1/test-idem/echo", c.token(), key, Map.of("a", 1)).getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
    }

    // AC4
    @Test
    void failed500AttemptIsProcessedAgainOnRetryAndNothingIsReplayedFromIt() {
        Ctx c = ctx();
        String key = UUID.randomUUID().toString();
        int before = IdempotencyEchoController.CALLS.get();
        IdempotencyEchoController.FAIL_NEXT.set(true);
        ResponseEntity<Map> failed = post("/v1/test-idem/echo", c.token(), key, Map.of("a", 1));
        assertThat(failed.getStatusCode().is5xxServerError()).isTrue();
        assertThat(keyRows(c.tenant().id())).as("5xx releases the key").isZero();
        ResponseEntity<Map> retry = post("/v1/test-idem/echo", c.token(), key, Map.of("a", 1));
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(IdempotencyEchoController.CALLS.get() - before).isEqualTo(2);
    }

    @Test
    void validationErrorReleasesTheKeySoACorrectedRequestWithTheSameKeyRuns() {
        Ctx c = ctx();
        String key = UUID.randomUUID().toString();
        String patientId = registerPatient(c.token());
        ResponseEntity<Map> bad = post("/v1/queue/check-in", c.token(), key, Map.of("patientId", "not-a-uuid"));
        assertThat(bad.getStatusCode().is4xxClientError()).isTrue();
        ResponseEntity<Map> fixed = post("/v1/queue/check-in", c.token(), key, Map.of("patientId", patientId, "doctorId", c.staff().id().toString()));
        assertThat(fixed.getStatusCode().is2xxSuccessful()).as(String.valueOf(fixed.getBody())).isTrue();
        assertThat(queueEntries(c)).isEqualTo(1);
    }

    @Test
    void permissionDeniedAttemptDoesNotKeepOrReplayTheKey() {
        Ctx c = ctx();
        UUID noBilling = seedRole(c.tenant().id(), "NoBilling", false, fullGrant("queue"));
        String token = loginAndGetAccessToken(seedStaff(c.tenant(), noBilling, "nb" + c.tenant().id() + "@a.com", phone(), false));
        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> denied = post("/v1/billing/otc-checkout", token, key, Map.of("lineItems", List.of()));
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(keyRows(c.tenant().id())).isZero();
    }

    // AC5
    @Test
    void everyAnnotatedRealEndpointRefusesAMissingOrMalformedKeyWith400() {
        Ctx c = ctx();
        String someId = UUID.randomUUID().toString();
        Map<String, Object> checkIn = Map.of("patientId", someId, "doctorId", c.staff().id().toString());
        Map<String, Object> patient = Map.of("name", "X Y", "phone", phone(), "dob", "1990-01-01", "gender", "male");
        Map<String, Object> payment = Map.of("method", "cash", "amount", 1);
        Map<String, Object> bill = Map.of("lineItems", List.of(Map.of("override", Map.of("reasonCode", "other"), "chargeCode", "C", "quantity", 1)));
        Map<String, Map.Entry<String, Object>> targets = new java.util.LinkedHashMap<>();
        targets.put("check-in", Map.entry("/v1/queue/check-in", checkIn));
        targets.put("register", Map.entry("/v1/patients", patient));
        targets.put("payment", Map.entry("/v1/billing/invoices/" + someId + "/payments", payment));
        targets.put("checkout", Map.entry("/v1/billing/checkout/" + someId, bill));
        targets.put("otc", Map.entry("/v1/billing/otc-checkout", bill));
        targets.put("book", Map.entry("/v1/appointments", Map.of("doctorId", c.staff().id().toString(), "patientId", someId, "startTime", "2030-01-01T10:00:00Z")));
        targets.put("rx sign", Map.entry("/v1/clinical/prescriptions/" + someId + "/sign", Map.of()));
        for (var t : targets.entrySet()) {
            for (String bad : new String[]{null, "", "not-a-uuid", "12345"}) {
                ResponseEntity<Map> r = post(t.getValue().getKey(), c.token(), bad, t.getValue().getValue());
                assertThat(r.getStatusCode()).as(t.getKey() + " key=" + bad).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(r.getBody().get("code")).as(t.getKey() + " key=" + bad).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
            }
        }
        assertThat(keyRows(c.tenant().id())).isZero();
    }

    @Test
    void unannotatedPostsNeedNoKeyAndUnauthenticatedCallsStay401() {
        Ctx c = ctx();
        assertThat(post("/v1/test-idem/plain", c.token(), null, Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/v1/test-idem/echo", null, UUID.randomUUID().toString(), Map.of("a", 1)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post("/v1/test-idem/echo", null, null, Map.of("a", 1)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theRequiredEndpointsCarryTheAnnotation() {
        Set<String> annotated = mappings.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getValue().hasMethodAnnotation(Idempotent.class))
                .flatMap(e -> e.getKey().getPathPatternsCondition().getPatternValues().stream().map(p -> e.getKey().getMethodsCondition().getMethods() + " " + p))
                .collect(Collectors.toSet());
        for (String needed : List.of("[POST] /v1/queue/check-in", "[POST] /v1/patients", "[POST] /v1/appointments",
                "[POST] /v1/billing/invoices/{id}/payments", "[POST] /v1/billing/checkout/{queueEntryId}", "[POST] /v1/billing/otc-checkout",
                "[POST] /v1/clinical/prescriptions/{queueEntryId}/sign")) {
            assertThat(annotated).as("annotated endpoints").contains(needed);
        }
    }

    @Test
    void registrationWithTheSameKeyCreatesOnePatient() {
        Ctx c = ctx();
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("name", "Dup Check " + UUID.randomUUID().toString().substring(0, 8), "phone", phone(), "dob", "1991-02-03", "gender", "female");
        ResponseEntity<Map> a = post("/v1/patients", c.token(), key, body);
        ResponseEntity<Map> b = post("/v1/patients", c.token(), key, body);
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(b.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(b.getBody().get("id")).isEqualTo(a.getBody().get("id"));
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM patients WHERE name = ?", Integer.class, body.get("name")))).isEqualTo(1);
    }

    // AC6 + NFR
    @Test
    void rowsExpireAfter24hAndAreRemovedByThePurgeWhileFreshRowsStay() {
        Ctx c = ctx();
        Ctx other = ctx();
        String oldKey = UUID.randomUUID().toString();
        String freshKey = UUID.randomUUID().toString();
        String otherKey = UUID.randomUUID().toString();
        post("/v1/test-idem/echo", c.token(), oldKey, Map.of("a", 1));
        post("/v1/test-idem/echo", c.token(), freshKey, Map.of("a", 2));
        post("/v1/test-idem/echo", other.token(), otherKey, Map.of("a", 3));
        double hours = inTenantTx(c.tenant().id(), () -> jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (expires_at - created_at))/3600 FROM idempotency_keys WHERE idem_key = ?::uuid", Double.class, oldKey));
        assertThat(hours).isBetween(23.99, 24.01);
        su("UPDATE idempotency_keys SET expires_at = now() - interval '1 minute' WHERE idem_key = ?::uuid", oldKey);
        su("UPDATE idempotency_keys SET expires_at = now() - interval '1 minute' WHERE idem_key = ?::uuid", otherKey);
        assertThat(store.purgeExpired()).isGreaterThanOrEqualTo(2);
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForList("SELECT idem_key::text FROM idempotency_keys", String.class))).containsExactly(freshKey);
        assertThat(keyRows(other.tenant().id())).isZero();
    }

    @Test
    void anExpiredKeyIsTreatedAsNewAndRunsAgain() {
        Ctx c = ctx();
        String key = UUID.randomUUID().toString();
        int before = IdempotencyEchoController.CALLS.get();
        post("/v1/test-idem/echo", c.token(), key, Map.of("a", 1));
        su("UPDATE idempotency_keys SET expires_at = now() - interval '1 minute' WHERE idem_key = ?::uuid", key);
        ResponseEntity<Map> again = post("/v1/test-idem/echo", c.token(), key, Map.of("a", 9)); // even a different body: the old key is gone
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(IdempotencyEchoController.CALLS.get() - before).isEqualTo(2);
    }

    // NFR: response bodies encrypted at rest
    @Test
    void storedResponseBodyIsCiphertextAndTheRequestBodyIsNotStored() throws Exception {
        Ctx c = ctx();
        String key = UUID.randomUUID().toString();
        String marker = "PHIMARKER" + UUID.randomUUID().toString().substring(0, 8);
        post("/v1/test-idem/echo", c.token(), key, Map.of("note", marker));
        try (Connection con = superuserConnection(); PreparedStatement ps = con.prepareStatement("SELECT * FROM idempotency_keys WHERE idem_key = ?::uuid")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                byte[] enc = rs.getBytes("response_body_enc");
                assertThat(enc).isNotNull();
                assertThat(new String(enc, StandardCharsets.ISO_8859_1)).doesNotContain(marker);
                assertThat(rs.getString("status")).isEqualTo("completed");
                assertThat(rs.getInt("response_status")).isEqualTo(200);
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    Object v = rs.getObject(i);
                    if (v instanceof byte[] bytes) assertThat(new String(bytes, StandardCharsets.ISO_8859_1)).doesNotContain(marker);
                    else if (v != null) assertThat(v.toString()).doesNotContain(marker);
                }
            }
        }
    }

    // scoping + RLS
    @Test
    void keysAreScopedPerTenantAndPerActorAndRowsAreRlsForced() {
        Ctx a = ctx();
        Ctx b = ctx();
        UUID role = a.staff().roleId();
        String a2 = loginAndGetAccessToken(seedStaff(a.tenant(), role, "second" + a.tenant().id() + "@a.com", phone(), false));
        String key = UUID.randomUUID().toString();
        int before = IdempotencyEchoController.CALLS.get();
        ResponseEntity<Map> r1 = post("/v1/test-idem/echo", a.token(), key, Map.of("who", "a"));
        ResponseEntity<Map> r2 = post("/v1/test-idem/echo", a2, key, Map.of("who", "a2"));
        ResponseEntity<Map> r3 = post("/v1/test-idem/echo", b.token(), key, Map.of("who", "b"));
        for (ResponseEntity<Map> r : List.of(r1, r2, r3)) {
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(r.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        }
        assertThat(IdempotencyEchoController.CALLS.get() - before).isEqualTo(3);
        assertThat(keyRows(a.tenant().id())).isEqualTo(2);
        assertThat(keyRows(b.tenant().id())).isEqualTo(1);
        List<Map<String, Object>> flags = jdbc.queryForList("SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = 'idempotency_keys'");
        assertThat(flags).singleElement().satisfies(f -> assertThat(f).containsEntry("relrowsecurity", true).containsEntry("relforcerowsecurity", true));
        // tenant B cannot see tenant A's rows by key
        assertThat(inTenantTx(b.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE tenant_id = ?", Integer.class, a.tenant().id()))).isZero();
    }
}
