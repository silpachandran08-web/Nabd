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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-026: Idempotency-Key replay, reuse, in-progress, 5xx release, missing header, purge, tenant isolation. */
class IdempotencyApiTest extends ApiTestBase {

    @Autowired IdempotencyStore store;

    private HttpEntity<String> withKey(String token, String key, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
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

    private String login(String email, String phone, SeededTenant t) {
        return loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), email, phone, false));
    }

    private int rows(UUID tenantId) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject("SELECT count(*) FROM idempotency_keys", Integer.class));
    }

    @Test
    void retryWithSameKeyReplaysOriginalResponseAndRunsOnce() {
        SeededTenant t = seedTenant();
        String token = login("i1@a.com", "+919800000001", t);
        int before = IdempotencyEchoController.CALLS.get();
        String key = UUID.randomUUID().toString();

        ResponseEntity<Map> first = post("/v1/test-idem/echo", token, key, Map.of("a", 1));
        ResponseEntity<Map> second = post("/v1/test-idem/echo", token, key, Map.of("a", 1));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(IdempotencyEchoController.CALLS.get() - before).isEqualTo(1);
    }

    @Test
    void paymentRetryReturnsOriginal200BodyAndLeavesOnePaymentRow() {
        SeededTenant t = seedTenant();
        SeededStaff staff = seedStaff(t, seedFullAccessRole(t.id()), "i2@a.com", "+919800000002", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Idem Pay", "phone", "+919800010002", "dob", "1990-01-01", "gender", "male")), Map.class).getBody().get("id");
        String entry = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", staff.id().toString())), Map.class).getBody().get("id");
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending")) {
            exchange("/v1/queue/" + entry + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", s)), Map.class);
        }
        String invoice = (String) exchange("/v1/billing/checkout/" + entry, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("chargeCode", "X", "chargeName", "X", "category", "Service",
                        "quantity", 1, "unitPrice", 1000, "taxRatePercent", 0)))), Map.class).getBody().get("id");
        String path = "/v1/billing/invoices/" + invoice + "/payments";
        String key = UUID.randomUUID().toString();
        Map<String, Object> pay = Map.of("method", "cash", "amount", 400);

        ResponseEntity<Map> first = post(path, token, key, pay);
        ResponseEntity<Map> retry = post(path, token, key, pay);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getStatusCode()).isEqualTo(first.getStatusCode());
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM invoice_payments", Integer.class))).isEqualTo(1);
        // stored response is ciphertext, never the plaintext body
        byte[] enc = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT response_body_enc FROM idempotency_keys WHERE idem_key = ?::uuid", byte[].class, key));
        assertThat(new String(enc, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("invoiceNumber");
    }

    @Test
    void duplicateCheckInsFiredTogetherCreateOneQueueEntry() throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff staff = seedStaff(t, seedFullAccessRole(t.id()), "i3@a.com", "+919800000003", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Idem Race", "phone", "+919800010003", "dob", "1991-01-01", "gender", "male")), Map.class).getBody().get("id");
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("patientId", patientId, "doctorId", staff.id().toString());

        List<ResponseEntity<Map>> results = race(2, () -> post("/v1/queue/check-in", token, key, body));

        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM queue_entries WHERE patient_id = ?",
                Integer.class, UUID.fromString(patientId)))).isEqualTo(1);
        for (ResponseEntity<Map> r : results) {
            boolean original = r.getStatusCode() == HttpStatus.CREATED;
            boolean inProgress = r.getStatusCode() == HttpStatus.CONFLICT && "IDEMPOTENCY_IN_PROGRESS".equals(r.getBody().get("code"));
            assertThat(original || inProgress).as("status %s body %s", r.getStatusCode(), r.getBody()).isTrue();
        }
        assertThat(results.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED && r.getHeaders().getFirst("Idempotent-Replayed") == null)).hasSize(1);
    }

    @Test
    void concurrentDuplicateWhileFirstIsRunningGets409InProgress() throws Exception {
        SeededTenant t = seedTenant();
        String token = login("i4@a.com", "+919800000004", t);
        String key = UUID.randomUUID().toString();
        int before = IdempotencyEchoController.CALLS.get();
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<ResponseEntity<Map>> slow = ex.submit(() -> post("/v1/test-idem/echo", token, key, Map.of("sleepMs", 1500)));
        Thread.sleep(500);

        ResponseEntity<Map> dup = post("/v1/test-idem/echo", token, key, Map.of("sleepMs", 1500));

        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dup.getBody().get("code")).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
        assertThat(slow.get().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(IdempotencyEchoController.CALLS.get() - before).isEqualTo(1);
        ex.shutdown();
    }

    @Test
    void sameKeyWithDifferentBodyIs422() {
        SeededTenant t = seedTenant();
        String token = login("i5@a.com", "+919800000005", t);
        String key = UUID.randomUUID().toString();
        post("/v1/test-idem/echo", token, key, Map.of("a", 1));

        ResponseEntity<Map> reused = post("/v1/test-idem/echo", token, key, Map.of("a", 2));

        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(reused.getBody().get("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void failedAttemptReleasesTheKeySoTheRetryRunsAgain() {
        SeededTenant t = seedTenant();
        String token = login("i6@a.com", "+919800000006", t);
        String key = UUID.randomUUID().toString();
        IdempotencyEchoController.FAIL_NEXT.set(true);

        ResponseEntity<Map> failed = post("/v1/test-idem/echo", token, key, Map.of("a", 1));
        ResponseEntity<Map> retry = post("/v1/test-idem/echo", token, key, Map.of("a", 1));

        assertThat(failed.getStatusCode().is5xxServerError()).isTrue();
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isNull();
    }

    @Test
    void clientErrorAlsoReleasesTheKey() {
        SeededTenant t = seedTenant();
        String token = login("i7@a.com", "+919800000007", t);
        String key = UUID.randomUUID().toString();

        ResponseEntity<Map> bad = post("/v1/queue/check-in", token, key, Map.of("patientId", "not-a-uuid"));
        assertThat(bad.getStatusCode().is4xxClientError()).isTrue();

        assertThat(rows(t.id())).isZero();
    }

    @Test
    void missingOrMalformedKeyOnIdempotentEndpointIs400AndPlainEndpointsNeedNone() {
        SeededTenant t = seedTenant();
        String token = login("i8@a.com", "+919800000008", t);

        ResponseEntity<Map> none = post("/v1/test-idem/echo", token, null, Map.of("a", 1));
        ResponseEntity<Map> junk = post("/v1/test-idem/echo", token, "not-a-uuid", Map.of("a", 1));
        ResponseEntity<Map> plain = post("/v1/test-idem/plain", token, null, Map.of());

        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(none.getBody().get("code")).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(junk.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(plain.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void unauthenticatedCallStaysA401() {
        ResponseEntity<String> r = http.exchange(url("/v1/test-idem/echo"), HttpMethod.POST, jsonBody(Map.of("a", 1)), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void keysAreTenantScopedAndRowsExpireAfter24hAndArePurged() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        String tokenA = login("i9a@a.com", "+919800000009", a);
        String tokenB = login("i9b@a.com", "+919800000010", b);
        String key = UUID.randomUUID().toString();
        post("/v1/test-idem/echo", tokenA, key, Map.of("a", 1));

        // same key, other tenant: independent (first execution, not a replay) and A's row is invisible to B
        ResponseEntity<Map> other = post("/v1/test-idem/echo", tokenB, key, Map.of("a", 1));
        assertThat(other.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(rows(a.id())).isEqualTo(1);
        assertThat(inTenantTx(b.id(), () -> jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE tenant_id = ?", Integer.class, a.id()))).isZero();

        Integer hours = inTenantTx(a.id(), () -> jdbc.queryForObject(
                "SELECT round(extract(epoch FROM expires_at - created_at) / 3600)::int FROM idempotency_keys", Integer.class));
        assertThat(hours).isEqualTo(24);

        // backdate tenant A's row past expiry (superuser bypasses RLS), purge removes it and leaves B's
        new org.springframework.jdbc.core.JdbcTemplate(superuserDataSource()).update(
                "UPDATE idempotency_keys SET expires_at = now() - interval '1 minute' WHERE tenant_id = ?", a.id());
        assertThat(store.purgeExpired()).isGreaterThanOrEqualTo(1);
        assertThat(rows(a.id())).isZero();
        assertThat(rows(b.id())).isEqualTo(1);
    }

    private <T> List<T> race(int n, java.util.concurrent.Callable<T> call) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(ex.submit(() -> { ready.countDown(); go.await(); return call.call(); }));
        }
        ready.await();
        go.countDown();
        List<T> out = new ArrayList<>();
        for (Future<T> f : fs) out.add(f.get());
        ex.shutdown();
        return out;
    }
}
