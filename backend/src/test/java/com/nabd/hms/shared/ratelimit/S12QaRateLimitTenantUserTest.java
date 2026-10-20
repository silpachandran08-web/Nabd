package com.nabd.hms.shared.ratelimit;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ValkeyTestSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S12 QA-2, PLT-008 tenant and user dimensions over HTTP against a real Redis 6.2 (Valkey stand-in). Refill is set to
 * 1 token/min so bucket boundaries are exact within a short test: tenant burst 30, user burst 12. Plans re-read every 2 s.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class S12QaRateLimitTenantUserTest extends ApiTestBase {

    static final ValkeyTestSupport VALKEY = ValkeyTestSupport.start();

    @DynamicPropertySource
    static void valkey(DynamicPropertyRegistry r) {
        r.add("app.valkey.url", VALKEY::url);
        r.add("app.rate-limit.tenant-per-minute", () -> "1");
        r.add("app.rate-limit.tenant-burst", () -> "30");
        r.add("app.rate-limit.user-per-minute", () -> "1");
        r.add("app.rate-limit.user-burst", () -> "12");
        r.add("app.rate-limit.ip-per-minute", () -> "100000");
        r.add("app.rate-limit.ip-burst", () -> "100000");
        r.add("app.rate-limit.plan-refresh-seconds", () -> "2");
    }

    @AfterAll
    static void stopValkey() {
        VALKEY.close();
    }

    @Autowired
    MeterRegistry meters;
    @Autowired
    TokenBuckets buckets;

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    // ---- AC1: tenant burst -> 429 rate_limited + Retry-After; other tenants unaffected ----

    @Test
    void ac1_tenantBurstBoundaryExactly30ThenProblem429WithRetryAfterOtherTenantUnaffected() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        List<String> users = users(a, 4);
        String bUser = users(b, 1).get(0);
        int ok = 0;
        for (int u = 0; u < 3; u++) {
            for (int i = 0; i < 10; i++) {
                assertThat(call(users.get(u)).getStatusCode()).as("call %d of user %d", i, u).isEqualTo(HttpStatus.OK);
                ok++;
            }
        }
        assertThat(ok).isEqualTo(30);
        ResponseEntity<Map> denied = call(users.get(3)); // a 4th user with a full user bucket: the tenant bucket is empty
        assertProblem(denied, "tenant");
        long retry = Long.parseLong(denied.getHeaders().getFirst("Retry-After"));
        assertThat(retry).as("1 token/min refill -> retry within a minute").isBetween(1L, 60L);
        assertThat(call(bUser).getStatusCode()).as("other tenant on the same replica").isEqualTo(HttpStatus.OK);
    }

    @Test
    void ac1_abuseSpoofedTenantAndIpHeadersDoNotMoveTheRequestToAnotherBucket() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        List<String> users = users(a, 4);
        for (int u = 0; u < 3; u++) for (int i = 0; i < 10; i++) call(users.get(u));
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(users.get(3));
        h.set("X-Tenant-Id", b.id().toString());
        h.set("X-Tenant-Slug", b.slug());
        h.set("X-Forwarded-For", "203.0.113.77");
        h.set("X-Real-IP", "203.0.113.78");
        h.set("Forwarded", "for=203.0.113.79");
        ResponseEntity<Map> r = asMap(exchange("/v1/auth/sessions", HttpMethod.GET, new HttpEntity<>(h), String.class));
        assertProblem(r, "tenant");
    }

    // ---- per user/session (story context: 120/min, scaled to burst 12 here) ----

    @Test
    void userBucketBoundaryAndASecondSignInDoesNotRefillIt() {
        SeededTenant t = seedTenant();
        UUID role = role(t);
        SeededStaff s = seedStaff(t, role, "qa2-u" + tag() + "@rl.test", phone(), false);
        String session1 = loginAndGetAccessToken(s);
        String colleague = users(t, 1).get(0);
        for (int i = 0; i < 12; i++) assertThat(call(session1).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertProblem(call(session1), "user");
        String session2 = loginAndGetAccessToken(s); // sign-in is anonymous (ip bucket); the user bucket is per staff id
        assertProblem(call(session2), "user");
        assertThat(call(colleague).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- AC5: plan quota applies within 60 s (2 s here) ----

    @Test
    void ac5_higherPlanQuotaAppliesAfterTheRefreshWindowAndOnlyForThatTenant() throws Exception {
        SeededTenant t = seedTenant();
        SeededTenant neighbour = seedTenant();
        List<String> users = users(t, 6);
        assertThat(call(users.get(0)).getStatusCode()).isEqualTo(HttpStatus.OK); // cached at the default burst 30
        UUID plan = seedPlan("qa2-rl-" + tag(), 1000, "INR", 50);
        su.update("UPDATE master.plans SET api_requests_per_minute = 6000, api_burst = 200 WHERE id = ?", plan);
        subscribe(t.id(), plan);
        Thread.sleep(2500);
        for (String u : users) {
            for (int i = 0; i < (u.equals(users.get(0)) ? 9 : 10); i++) {
                assertThat(call(u).getStatusCode()).as("60 calls fit the plan's 200, not the default 30").isEqualTo(HttpStatus.OK);
            }
        }
        // the neighbour without a plan still has the default 30
        List<String> n = users(neighbour, 4);
        int ok = 0;
        for (String u : n) for (int i = 0; i < 10; i++) if (call(u).getStatusCode() == HttpStatus.OK) ok++;
        assertThat(ok).isEqualTo(30);
    }

    @Test
    void ac5_boundaryLowerPlanQuotaIsTheBucketSizeFromTheFirstRequest() {
        SeededTenant t = seedTenant();
        UUID plan = seedPlan("qa2-rl-lo-" + tag(), 1000, "INR", 50);
        su.update("UPDATE master.plans SET api_requests_per_minute = 1, api_burst = 5 WHERE id = ?", plan);
        subscribe(t.id(), plan);
        List<String> users = users(t, 2);
        for (int i = 0; i < 5; i++) assertThat(call(users.get(i % 2)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertProblem(call(users.get(1)), "tenant");
    }

    // ---- AC4: Valkey down -> tenant/user limits fail open, alert metric, back to normal after restart ----

    @Test
    void ac4_valkeyDownTenantAndUserLimitsFailOpenWithTheAlertMetricAndComeBack() throws Exception {
        SeededTenant t = seedTenant();
        String u = users(t, 1).get(0);
        double before = failOpen("user") + failOpen("tenant");
        VALKEY.stop();
        try {
            for (int i = 0; i < 16; i++) { // past the user burst of 12
                assertThat(call(u).getStatusCode()).as("API keeps serving while Valkey is down").isEqualTo(HttpStatus.OK);
            }
            assertThat(failOpen("user") + failOpen("tenant")).isGreaterThan(before);
        } finally {
            VALKEY.restart();
        }
        TokenBuckets.Decision back = null;
        for (int i = 0; i < 60 && back == null; i++) {
            Thread.sleep(500);
            back = buckets.take(List.of(new TokenBuckets.Bucket("rl:probe:{" + UUID.randomUUID() + "}", 60, 60)));
        }
        assertThat(back).as("limiter reconnects").isNotNull();
        // the restarted (empty) store applies limits again: a fresh bucket of 12 for this user
        int ok = 0;
        ResponseEntity<Map> r;
        while ((r = call(u)).getStatusCode() == HttpStatus.OK && ok < 30) ok++;
        assertThat(ok).isEqualTo(12);
        assertProblem(r, "user");
    }

    @Test
    void keysHoldOnlyIdsNeverEmailsOrPhones() {
        SeededTenant t = seedTenant();
        String email = "qa2-keys" + tag() + "@rl.test";
        String ph = phone();
        String access = loginAndGetAccessToken(seedStaff(t, role(t), email, ph, false));
        call(access);
        var keys = new org.springframework.data.redis.core.StringRedisTemplate(connection()).keys("rl:*");
        assertThat(keys).isNotEmpty();
        assertThat(keys).noneMatch(k -> k.contains("@") || k.contains(ph.substring(1)) || k.contains("127.0.0.1"));
    }

    // ---- helpers ----

    private org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory connection() {
        var f = new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory("127.0.0.1", VALKEY.port());
        f.afterPropertiesSet();
        f.start();
        return f;
    }

    private void subscribe(UUID tenantId, UUID plan) {
        su.update("INSERT INTO master.subscriptions (tenant_id, plan_id, mrr_cents, currency, renewal_date) VALUES (?,?,1000,'INR',current_date + 30)",
                tenantId, plan);
    }

    private double failOpen(String dim) {
        return meters.find("nabd.rate_limit.fail_open").tag("dimension", dim).counters().stream().mapToDouble(Counter::count).sum();
    }

    private UUID role(SeededTenant t) {
        return seedRole(t.id(), "QA2 RL " + tag(), false, new ModuleGrant("queue", true, false, false, false, false, false, false));
    }

    private List<String> users(SeededTenant t, int n) {
        UUID role = role(t);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(loginAndGetAccessToken(seedStaff(t, role, "qa2-" + tag() + "@rl.test", phone(), false)));
        return out;
    }

    private static String phone() {
        return "+9194" + String.format("%08d", Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000_000L));
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private ResponseEntity<Map> call(String token) {
        return asMap(exchange("/v1/auth/sessions", HttpMethod.GET, authed(token), String.class));
    }

    private ResponseEntity<Map> asMap(ResponseEntity<String> r) {
        try {
            Map body = r.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS ? objectMapper.readValue(r.getBody(), Map.class) : Map.of();
            return new ResponseEntity<>(body, r.getHeaders(), r.getStatusCode());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static void assertProblem(ResponseEntity<Map> r, String dimension) {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(r.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(String.valueOf(r.getBody().get("type"))).endsWith("/rate_limited");
        assertThat(r.getBody().get("status")).isEqualTo(429);
        assertThat(r.getBody().get("dimension")).isEqualTo(dimension);
        assertThat(Long.parseLong(r.getHeaders().getFirst("Retry-After"))).isPositive();
    }
}
