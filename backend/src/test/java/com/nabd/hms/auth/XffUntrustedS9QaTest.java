package com.nabd.hms.auth;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-003 XFF: untrusted peer (default config), per-IP limit lowered to 3 so the limiter is observable. */
class XffUntrustedS9QaTest extends ApiTestBase {

    private JdbcTemplate su() { return new JdbcTemplate(superuserDataSource()); }

    private ResponseEntity<String> post(String path, String xff, Object body, String bearer) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (xff != null) h.add("X-Forwarded-For", xff);
        if (bearer != null) h.setBearerAuth(bearer);
        return http.exchange(url(path), HttpMethod.POST, new HttpEntity<>(writeJson(body), h), String.class);
    }

    private String writeJson(Object o) {
        try { return objectMapper.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static boolean loopback(String ip) { return List.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1").contains(ip); }

    /** Limiter observability: the suite runs with limit 100000, so fill the peer's last-minute budget and expect 429 despite rotating XFF. */
    @Test
    void ac5_spoofedXffDoesNotChangeStoredIp_andTheLimiterCountsThePeer() {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "qa-xff@a.com", "+919700000001", false);
        post("/v1/auth/login", "1.2.3.4", Map.of("tenantSlug", s.slug(), "email", "nobody@a.com", "pin", "999999"), null);
        List<String> ips = su().queryForList("SELECT DISTINCT host(ip_address) FROM login_attempts WHERE tenant_id = ?", String.class, t.id());
        assertThat(ips).as("rows were written (positive control)").isNotEmpty();
        assertThat(ips).allMatch(XffUntrustedS9QaTest::loopback);
        assertThat(ips).noneMatch(i -> i.startsWith("1.2.3."));
        try {
            su().update("INSERT INTO login_attempts (tenant_id, staff_id, email, ip_address, succeeded) "
                    + "SELECT ?, NULL, 'qa-ratelimit-fill', ip::inet, false FROM generate_series(1,100001) g, (VALUES ('127.0.0.1'),('::1')) v(ip)", t.id());
            int[] codes = new int[3];
            for (int i = 0; i < codes.length; i++) {
                codes[i] = post("/v1/auth/login", "1.2.3." + (i + 10), Map.of("tenantSlug", s.slug(), "email", "nobody" + i + "@a.com", "pin", "999999"), null).getStatusCode().value();
            }
            System.out.println("QA-XFF statuses after filling the peer budget: " + java.util.Arrays.toString(codes));
            assertThat(java.util.Arrays.stream(codes).boxed()).as("per-IP limiter must trip").allMatch(c -> c == 429);
        } finally {
            su().update("DELETE FROM login_attempts WHERE email = 'qa-ratelimit-fill'");
        }
    }

    @Test
    void ac6_malformedXffNeverCauses500_onAnyPublicOrAuthedEndpoint() {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "qa-xff2@a.com", "+919700000002", false);
        String access = loginAndGetAccessToken(s);
        List<String> junk = List.of("not-an-ip", "999.1.1.1", "1.2.3.4, <script>", "'; drop table staff;--", "", " ", ",,,", "::ffff:1.2.3.4:99999",
                "fe80::1%eth0", "1.2.3.4/24", "0x7f.0.0.1", "a".repeat(5000), "1.2.3.4\t5.6.7.8", "[::1]:80");
        int total = 0;
        for (String x : junk) {
            if (x.isEmpty() || x.isBlank() && x.length() < 2) { /* empty header values are legal to send */ }
            List<ResponseEntity<String>> rs = new java.util.ArrayList<>();
            rs.add(post("/v1/auth/login", x, Map.of("tenantSlug", s.slug(), "email", s.email(), "pin", "999999"), null));
            rs.add(post("/v1/auth/otp/request", x, Map.of("tenantSlug", s.slug(), "mobilePhone", "+919700000002"), null));
            rs.add(post("/v1/auth/pin/reset-request", x, Map.of("tenantSlug", s.slug(), "mobilePhone", "+919700000002"), null));
            rs.add(post("/v1/auth/refresh", x, Map.of("refreshToken", "nope"), null));
            rs.add(post("/v1/auth/unlock", x, Map.of("pin", "999999"), access));
            rs.add(post("/v1/platform/auth/login", x, Map.of("email", "a@b.c", "pin", "123456"), null));
            rs.add(post("/v1/owners/auth/login", x, Map.of("email", "a@b.c", "pin", "123456"), null));
            for (var r : rs) {
                total++;
                assertThat(r.getStatusCode().is5xxServerError()).as("XFF '" + (x.length() > 30 ? x.substring(0, 30) : x) + "' -> " + r.getStatusCode()).isFalse();
            }
        }
        System.out.println("QA-XFF malformed calls=" + total);
        // every stored row has a valid peer address
        List<String> ips = su().queryForList("SELECT DISTINCT host(ip_address) FROM login_attempts WHERE tenant_id = ?", String.class, t.id());
        assertThat(ips).isNotEmpty().allMatch(XffUntrustedS9QaTest::loopback);
    }

    @Test
    void ac5_ac6_auditLogIpIsThePeer_forSpoofedAndMalformedHeaders() {
        SeededTenant t = seedTenant();
        List<SeededStaff> ss = new java.util.ArrayList<>();
        for (String xff : List.of("1.2.3.4", "not-an-ip")) {
            SeededStaff s = seedStaff(t, seedBreakGlassDoctorRole(t.id()), "qa-xff3-" + ss.size() + "@a.com", "+91970000010" + ss.size(), false);
            ss.add(s);
            String token = loginAndGetAccessToken(s);
            HttpHeaders extra = new HttpHeaders();
            extra.add("X-Forwarded-For", xff);
            assertThat(activateBreakGlass(token, "qa xff audit check reason", extra).getStatusCode()).as(xff).isEqualTo(HttpStatus.CREATED);
        }
        List<String> ips = su().queryForList("SELECT host(ip_address) FROM audit_log WHERE actor_id IN (?, ?) AND action = 'auth.break_glass_activated'", String.class, ss.get(0).id(), ss.get(1).id());
        assertThat(ips).hasSize(2).allMatch(XffUntrustedS9QaTest::loopback);
    }
}
