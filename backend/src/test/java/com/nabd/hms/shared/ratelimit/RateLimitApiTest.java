package com.nabd.hms.shared.ratelimit;

import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.common.WhatsAppOtpSender;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ValkeyTestSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLT-008 over HTTP with a real Redis 6.2 (Valkey stand-in). Small limits so a test stays short: tenant 60/min burst 40,
 * user 30/min burst 20, plans re-read every 2 s. The shipped defaults (600/1,200, 120, 20, 30 s) are checked in
 * RateLimitConfigTest and the 1,200 burst itself in TokenBucketsTest.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class RateLimitApiTest extends ApiTestBase {

    static final ValkeyTestSupport VALKEY = ValkeyTestSupport.start();

    @DynamicPropertySource
    static void valkey(DynamicPropertyRegistry r) {
        r.add("app.valkey.url", VALKEY::url);
        r.add("app.rate-limit.tenant-per-minute", () -> "60");
        r.add("app.rate-limit.tenant-burst", () -> "40");
        r.add("app.rate-limit.user-per-minute", () -> "30");
        r.add("app.rate-limit.user-burst", () -> "20");
        r.add("app.rate-limit.ip-per-minute", () -> "100000"); // the fixtures sign in over HTTP from 127.0.0.1
        r.add("app.rate-limit.ip-burst", () -> "100000");
        r.add("app.rate-limit.plan-refresh-seconds", () -> "2");
    }

    @AfterAll
    static void stopValkey() {
        VALKEY.close();
    }

    @TestConfiguration
    static class Captor {
        @Bean
        @Primary
        Sent sent() {
            return new Sent();
        }
    }

    static class Sent implements WhatsAppOtpSender {
        final List<String> to = new CopyOnWriteArrayList<>();

        @Override
        public void send(String mobilePhone, String code) {
            to.add(mobilePhone);
        }
    }

    @Autowired
    Sent sent;
    @Autowired
    OutboxDispatcher dispatcher;
    @Autowired
    StringRedisTemplate valkey;
    @Autowired
    TokenBuckets buckets;
    @Autowired
    MeterRegistry meters;
    @Autowired
    HealthEndpoint health;

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    private String user(SeededTenant t) {
        UUID role = seedRole(t.id(), "RL " + UUID.randomUUID().toString().substring(0, 6), false);
        return loginAndGetAccessToken(seedStaff(t, role, "rl" + UUID.randomUUID().toString().substring(0, 8) + "@rl.test",
                newPhone(), false));
    }

    private static String newPhone() {
        return "+9194" + String.format("%08d", Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000_000L));
    }

    /** GET /v1/auth/sessions (a JSON array when allowed); a 429 body is turned into a Map. */
    private ResponseEntity<Map> call(String token) {
        ResponseEntity<String> r = exchange("/v1/auth/sessions", HttpMethod.GET, authed(token), String.class);
        try {
            Map body = r.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS ? objectMapper.readValue(r.getBody(), Map.class) : Map.of();
            return new ResponseEntity<>(body, r.getHeaders(), r.getStatusCode());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Calls until the first 429 (at most max); returns the number of 2xx before it and the 429 (or null). */
    private record Run(int ok, ResponseEntity<Map> limited) {
    }

    private Run until429(String token, int max) {
        int ok = 0;
        for (int i = 0; i < max; i++) {
            ResponseEntity<Map> r = call(token);
            if (r.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                return new Run(ok, r);
            }
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
            ok++;
        }
        return new Run(ok, null);
    }

    private static void assertRateLimited(ResponseEntity<Map> r, String dimension) {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(r.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(r.getBody().get("type")).isEqualTo("https://api.nabd.health/errors/rate_limited");
        assertThat(r.getBody().get("status")).isEqualTo(429);
        assertThat(r.getBody().get("dimension")).isEqualTo(dimension);
        assertThat(r.getBody().get("traceId")).isNotNull();
        long retry = Long.parseLong(r.getHeaders().getFirst("Retry-After"));
        assertThat(retry).isPositive();
        assertThat(((Number) r.getBody().get("retryAfterSeconds")).longValue()).isEqualTo(retry);
    }

    private double counter(String name, String dimension) {
        return meters.find(name).tag("dimension", dimension).counters().stream().mapToDouble(Counter::count).sum();
    }

    @Test
    void userOverLimitGets429WithRetryAfterAndAColleagueIsUnaffected() {
        SeededTenant t = seedTenant();
        String u1 = user(t);
        String u2 = user(t);
        double before = counter("nabd.rate_limit.exceeded", "user");
        Run run = until429(u1, 30);
        assertThat(run.limited()).as("user bucket of 20 runs out").isNotNull();
        assertThat(run.ok()).isBetween(20, 22); // 30/min refills 1 token per 2 s while the loop runs
        assertRateLimited(run.limited(), "user");
        assertThat(counter("nabd.rate_limit.exceeded", "user")).isGreaterThan(before);
        assertThat(call(u2).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void tenantOverBurstGets429AndOtherTenantsOnTheSameReplicaAreUnaffected() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        String a1 = user(a), a2 = user(a), a3 = user(a);
        String b1 = user(b);
        long t0 = System.nanoTime();
        int ok = 0;
        for (int i = 0; i < 19; i++) {
            assertThat(call(a1).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(call(a2).getStatusCode()).isEqualTo(HttpStatus.OK);
            ok += 2;
        }
        Run run = until429(a3, 19);
        ok += run.ok();
        double seconds = (System.nanoTime() - t0) / 1e9;
        assertThat(run.limited()).as("tenant bucket of 40 runs out before a3's own 20").isNotNull();
        assertRateLimited(run.limited(), "tenant");
        assertThat(ok).isBetween(40, 40 + (int) Math.ceil(seconds) + 1); // 60/min = 1 token/s refill
        assertThat(call(b1).getStatusCode()).as("another tenant").isEqualTo(HttpStatus.OK);
        // more 429s for the same tenant publish one shared.rate_limit.exceeded.v1 per minute, not one per request
        for (int i = 0; i < 3; i++) {
            call(a1);
        }
        Integer events = su.queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = ? AND payload->>'dimension' = 'tenant'",
                Integer.class, a.id(), RateLimitFilter.EXCEEDED_EVENT);
        assertThat(events).isEqualTo(1);
        String payload = su.queryForObject("SELECT payload::text FROM outbox_events WHERE tenant_id = ? AND event_type = ? LIMIT 1",
                String.class, a.id(), RateLimitFilter.EXCEEDED_EVENT);
        assertThat(payload).contains("\"keyHash\"").contains("\"windowSeconds\": 60");
        for (int i = 0; i < 3; i++) dispatcher.poll(); // the log-only handler consumes it (no 'no handler' retry loop)
        assertThat(su.queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = ? AND status <> 'done'",
                Integer.class, a.id(), RateLimitFilter.EXCEEDED_EVENT)).isZero();
    }

    @Test
    void higherPlanQuotaAppliesWithinTheRefreshWindow() throws Exception {
        SeededTenant t = seedTenant();
        String[] users = {user(t), user(t), user(t), user(t)};
        assertThat(call(users[0]).getStatusCode()).isEqualTo(HttpStatus.OK); // tenant now cached on the config default (burst 40)
        UUID plan = seedPlan("rl-" + UUID.randomUUID().toString().substring(0, 8), 1000, "INR", 50);
        su.update("UPDATE master.plans SET api_requests_per_minute = 6000, api_burst = 200 WHERE id = ?", plan);
        su.update("INSERT INTO master.subscriptions (tenant_id, plan_id, mrr_cents, currency, renewal_date) VALUES (?,?,1000,'INR',current_date + 30)",
                t.id(), plan);
        Thread.sleep(2500); // > plan-refresh-seconds (2 here, 30 by default, the story allows 60)
        for (String u : users) {
            for (int i = 0; i < 18; i++) {
                assertThat(call(u).getStatusCode()).as("76 calls fit the plan's 200, not the default 40").isEqualTo(HttpStatus.OK);
            }
        }
    }

    @Test
    void anonymousCallsUseTheIpBucketButOtpRequestsStaySilent202() throws Exception {
        String key = "rl:ip:" + RateLimitFilter.hash("127.0.0.1");
        valkey.opsForHash().putAll(key, Map.of("t", "0", "ts", "99999999999999")); // drain this IP's bucket
        try {
            ResponseEntity<Map> login = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                    "tenantSlug", "no-such", "email", "x@rl.test", "pin", "0000")), Map.class);
            assertRateLimited(login, "ip");
            assertThat(valkey.keys("rl:*").stream().noneMatch(k -> k.contains("127.0.0.1") || k.contains("@"))).isTrue();
            SeededTenant t = seedTenant();
            ResponseEntity<String> otp = http.postForEntity(url("/v1/auth/otp/request"),
                    jsonBody(Map.of("tenantSlug", t.slug(), "mobilePhone", newPhone())), String.class);
            assertThat(otp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        } finally {
            valkey.delete(key);
        }
    }

    @Test
    void valkeyDownApiFailsOpenAlertsAndOtpLimitsStayOnPostgres() throws Exception {
        SeededTenant t = seedTenant();
        String u = user(t);
        UUID role = seedRole(t.id(), "RLO " + UUID.randomUUID().toString().substring(0, 6), false);
        String phone = newPhone();
        seedStaff(t, role, "rlo" + UUID.randomUUID().toString().substring(0, 8) + "@rl.test", phone, false);
        double failOpenBefore = counter("nabd.rate_limit.fail_open", "user");
        VALKEY.stop();
        try {
            for (int i = 0; i < 30; i++) { // past the user's 20
                assertThat(call(u).getStatusCode()).as("API keeps serving while Valkey is down").isEqualTo(HttpStatus.OK);
            }
            assertThat(counter("nabd.rate_limit.fail_open", "user")).as("alert metric nabd_rate_limit_fail_open_total").isGreaterThan(failOpenBefore);
            assertThat(health.healthForPath("redis").getStatus()).isEqualTo(Status.DOWN);
            // PLT-006 per-number limit (60 s resend gap) from Postgres: every answer is 202, only the first is sent
            for (int i = 0; i < 3; i++) {
                ResponseEntity<String> r = http.postForEntity(url("/v1/auth/otp/request"),
                        jsonBody(Map.of("tenantSlug", t.slug(), "mobilePhone", phone)), String.class);
                assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            }
            for (int i = 0; i < 5; i++) dispatcher.poll();
            assertThat(sent.to.stream().filter(phone::equals).count()).isEqualTo(1);
        } finally {
            VALKEY.restart();
        }
        TokenBuckets.Decision back = null;
        for (int i = 0; i < 60 && back == null; i++) {
            Thread.sleep(500);
            back = buckets.take(List.of(new TokenBuckets.Bucket("rl:probe:{" + UUID.randomUUID() + "}", 60, 60)));
        }
        assertThat(back).as("limits apply again once Valkey is back").isNotNull();
        assertThat(health.healthForPath("redis").getStatus()).isEqualTo(Status.UP);
    }
}
