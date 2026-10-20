package com.nabd.hms.shared.ratelimit;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.common.WhatsAppOtpSender;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ValkeyTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S12 QA-2, PLT-008 pre-auth IP bucket and OTP behaviour. IP bucket: burst 10, 1 token/min (exact boundary).
 * No HTTP sign-ins in this class, so the loopback IP bucket is only used by the test's own anonymous calls.
 * Tomcat trusts no proxy (default), so X-Forwarded-For must never change the bucket.
 */
// Own context (Valkey + rate-limit properties): closed after the class so its pool does not hold Postgres slots for the rest of the run.
@org.springframework.test.annotation.DirtiesContext
@SuppressWarnings({"rawtypes", "unchecked"})
class S12QaRateLimitIpOtpTest extends ApiTestBase {

    static final ValkeyTestSupport VALKEY = ValkeyTestSupport.start();

    @DynamicPropertySource
    static void valkey(DynamicPropertyRegistry r) {
        r.add("app.valkey.url", VALKEY::url);
        r.add("app.rate-limit.ip-per-minute", () -> "1");
        r.add("app.rate-limit.ip-burst", () -> "10");
    }

    @AfterAll
    static void stopValkey() {
        VALKEY.close();
    }

    @TestConfiguration
    static class Captor {
        @Bean
        @Primary
        Sent qa2Sent() {
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

    @BeforeEach
    void freshIpBucket() throws InterruptedException {
        for (int attempt = 0; ; attempt++) { // after the Valkey-down test the client may still be reconnecting
            try {
                Set<String> keys = valkey.keys("rl:ip:*");
                if (keys != null && !keys.isEmpty()) valkey.delete(keys);
                Thread.sleep(2100); // past TokenBuckets' 2 s skip window after a failure
                return;
            } catch (RuntimeException e) {
                if (attempt > 40) throw e;
                Thread.sleep(500);
            }
        }
    }

    @Test
    void ipBucketBoundaryTenAnonymousCallsThenProblem429() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(badLogin(null).getStatusCode()).as("anonymous call %d", i).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
        ResponseEntity<String> r = badLogin(null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        Map body = objectMapper.readValue(r.getBody(), Map.class);
        assertThat(String.valueOf(body.get("type"))).endsWith("/rate_limited");
        assertThat(body.get("dimension")).isEqualTo("ip");
        assertThat(r.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(Long.parseLong(r.getHeaders().getFirst("Retry-After"))).isBetween(1L, 60L);
    }

    @Test
    void abuseRotatingForwardedHeadersDoesNotEscapeTheIpBucket() {
        for (int i = 0; i < 10; i++) badLogin(null);
        for (int i = 0; i < 5; i++) {
            HttpHeaders h = new HttpHeaders();
            h.set("X-Forwarded-For", "198.51.100." + i);
            h.set("X-Real-IP", "198.51.100." + (i + 50));
            h.set("Forwarded", "for=198.51.100." + (i + 100));
            h.set("True-Client-IP", "198.51.100." + (i + 150));
            assertThat(badLogin(h).getStatusCode()).as("spoofed client ip %d", i).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    @Test
    void ac3_otpOverThePerNumberLimitIsStill202AndNothingMoreIsSentEvenWithDifferentlyWrittenNumbers() {
        SeededTenant t = seedTenant();
        String local = "94" + String.format("%08d", Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000_000L));
        String e164 = "+91" + local;
        String other = "+9193" + local.substring(2);
        UUID role = seedRole(t.id(), "QA2 OTP " + UUID.randomUUID().toString().substring(0, 6), false,
                new ModuleGrant("queue", true, false, false, false, false, false, false));
        seedStaff(t, role, "qa2-otp" + UUID.randomUUID().toString().substring(0, 6) + "@rl.test", e164, false);
        seedStaff(t, role, "qa2-otp2" + UUID.randomUUID().toString().substring(0, 6) + "@rl.test", other, false);
        for (int i = 0; i < 12; i++) badLogin(null); // even with this IP's API bucket empty, OTP stays on its own path
        List<String> spellings = List.of(e164, "+91 " + local.substring(0, 5) + " " + local.substring(5), "+91-" + local, local, e164);
        for (String s : spellings) {
            assertThat(otp(t.slug(), s).getStatusCode()).as("OTP request as '%s'", s).isEqualTo(HttpStatus.ACCEPTED);
        }
        assertThat(otp(t.slug(), other).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        for (int i = 0; i < 5; i++) dispatcher.poll();
        assertThat(sent.to.stream().filter(p -> p.replaceAll("\\D", "").endsWith(local)).count())
                .as("one WhatsApp OTP for the number however it is written").isEqualTo(1);
        assertThat(sent.to.stream().filter(p -> p.replaceAll("\\D", "").equals(other.substring(1))).count())
                .as("another number is not limited by the first").isEqualTo(1);
    }

    @Test
    void ac4_valkeyDownAnonymousCallsFailOpenAndOtpLimitsStayEnforcedFromPostgres() throws Exception {
        SeededTenant t = seedTenant();
        String e164 = "+9195" + String.format("%08d", Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000_000L));
        seedStaff(t, seedRole(t.id(), "QA2 OTP down", false, new ModuleGrant("queue", true, false, false, false, false, false, false)),
                "qa2-otpdown@rl.test", e164, false);
        VALKEY.stop();
        try {
            for (int i = 0; i < 15; i++) { // past the IP burst of 10
                assertThat(badLogin(null).getStatusCode()).as("fail open %d", i).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            }
            for (int i = 0; i < 4; i++) {
                assertThat(otp(t.slug(), e164).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            }
            for (int i = 0; i < 5; i++) dispatcher.poll();
            assertThat(sent.to.stream().filter(e164::equals).count()).as("Postgres per-number limit, fail closed").isEqualTo(1);
        } finally {
            VALKEY.restart();
        }
    }

    private ResponseEntity<String> badLogin(HttpHeaders extra) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (extra != null) h.putAll(extra);
        return http.postForEntity(url("/v1/auth/login"),
                new HttpEntity<>("{\"tenantSlug\":\"no-such-clinic\",\"email\":\"x@rl.test\",\"pin\":\"0000\"}", h), String.class);
    }

    private ResponseEntity<String> otp(String slug, String phone) {
        return http.postForEntity(url("/v1/auth/otp/request"), jsonBody(Map.of("tenantSlug", slug, "mobilePhone", phone)), String.class);
    }
}
