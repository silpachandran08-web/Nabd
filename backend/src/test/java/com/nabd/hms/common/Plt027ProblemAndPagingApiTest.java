package com.nabd.hms.common;

import com.nabd.hms.shared.security.Audience;
import com.nabd.hms.shared.security.TokenType;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PLT-027: bounded lists, clean Problem+JSON, API security headers. */
class Plt027ProblemAndPagingApiTest extends ApiTestBase {

    @org.springframework.beans.factory.annotation.Autowired
    private JwtEncoder encoder;

    private String staffToken() {
        SeededTenant tenant = seedTenant();
        SeededStaff owner = seedStaff(tenant, seedFullAccessRole(tenant.id()), "p27@a.com", "+919800027001", false);
        return loginAndGetAccessToken(owner);
    }

    private static final List<String> TENANT_LISTS = List.of("/v1/patients", "/v1/patients/registry", "/v1/staff",
            "/v1/appointments", "/v1/appointments/follow-ups", "/v1/clinical/encounters");

    @SuppressWarnings("unchecked")
    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "101", "1000000"})
    void everyListRefusesAnOutOfRangeLimitWithAValidationProblemNamingLimit(String limit) {
        String token = staffToken();
        for (String path : TENANT_LISTS) {
            // OPS-008: the registry's ceiling is 200 (one full registry page), every other list's is 100
            String bad = path.endsWith("/registry") && limit.equals("101") ? "201" : limit;
            ResponseEntity<Map> r = exchange(path + "?limit=" + bad, HttpMethod.GET, authed(token), Map.class);
            assertThat(r.getStatusCode()).as(path + " limit=" + limit).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(r.getHeaders().getContentType().toString()).contains("application/problem+json");
            assertThat((String) r.getBody().get("type")).endsWith("/validation");
            assertThat(((Map<String, String>) ((List<?>) r.getBody().get("errors")).get(0)).get("field")).isEqualTo("limit");
            assertThat(r.getBody().get("traceId")).isNotNull();
        }
    }

    @Test
    void platformListsAreBoundedToo() {
        SeededOperator op = seedOperator("p27-" + UUID.randomUUID() + "@nabd.health", "super_admin", false);
        String token = platformLoginAndGetAccessToken(op);
        for (String path : List.of("/v1/platform/tenants", "/v1/platform/audit-log", "/v1/platform/billing/subscriptions",
                "/v1/platform/billing/discounts")) {
            assertThat(exchange(path + "?limit=0", HttpMethod.GET, authed(token), Map.class).getStatusCode())
                    .as(path).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exchange(path + "?limit=1000", HttpMethod.GET, authed(token), Map.class).getStatusCode())
                    .as(path).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exchange(path + "?limit=100", HttpMethod.GET, authed(token), Map.class).getStatusCode())
                    .as(path).isEqualTo(HttpStatus.OK);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void boundariesAndDefaultPage() {
        String token = staffToken();
        for (int i = 0; i < 27; i++) {
            Map<String, Object> body = Map.of("name", "Bulk P" + i, "phone", String.format("+91981002%04d", i),
                    "dob", "1990-01-01", "gender", "female", "confirmedNotDuplicate", true);
            assertThat(exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, body), Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }
        Map<String, Object> dflt = exchange("/v1/patients", HttpMethod.GET, authed(token), Map.class).getBody();
        assertThat((List<?>) dflt.get("data")).hasSize(25);
        assertThat(((Map<String, Object>) dflt.get("page")).get("nextCursor")).isNotNull();
        assertThat(((Map<String, Object>) dflt.get("page")).get("limit")).isEqualTo(25);
        assertThat(exchange("/v1/patients?limit=1", HttpMethod.GET, authed(token), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> max = exchange("/v1/patients?limit=100", HttpMethod.GET, authed(token), Map.class).getBody();
        assertThat((List<?>) max.get("data")).hasSize(27);
    }

    @SuppressWarnings("unchecked")
    @Test
    void tokenMissingAnExpectedClaimIs401ProblemNot500() throws Exception {
        String real = staffToken();
        Map<String, Object> c = objectMapper.readValue(java.util.Base64.getUrlDecoder().decode(real.split("\\.")[1]), Map.class);
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now)
                .expiresAt(now.plusSeconds(300)).subject((String) c.get("sub"));
        c.forEach((k, v) -> {
            if (!List.of("iss", "iat", "exp", "nbf", "sub", "aud", "typ", "tenantId", "jti").contains(k)) b.claim(k, v);
        });
        TokenType.ACCESS.stamp(b, Audience.STAFF_API);
        String noTenant = encoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();

        ResponseEntity<Map> r = exchange("/v1/staff", HttpMethod.GET, authed(noTenant), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(r.getHeaders().getContentType().toString()).contains("application/problem+json");
        assertThat((String) r.getBody().get("type")).endsWith("/unauthenticated");
        assertThat(r.getBody().get("traceId")).isNotNull();
    }

    @Test
    void headersOnEveryResponseKind() {
        String token = staffToken();
        List<ResponseEntity<Map>> rs = new ArrayList<>();
        rs.add(exchange("/v1/staff", HttpMethod.GET, authed(token), Map.class));                    // 200
        rs.add(exchange("/v1/staff?limit=0", HttpMethod.GET, authed(token), Map.class));            // 400
        rs.add(exchange("/v1/staff", HttpMethod.GET, new org.springframework.http.HttpEntity<>(new HttpHeaders()), Map.class)); // 401
        rs.add(exchange("/v1/no-such-route", HttpMethod.GET, authed(token), Map.class));            // 404
        List<Integer> seen = rs.stream().map(r -> r.getStatusCode().value()).toList();
        assertThat(seen.get(0)).isEqualTo(200);
        assertThat(seen.get(1)).isEqualTo(400);
        assertThat(seen.get(2)).isEqualTo(401);
        assertThat(seen.get(3)).isGreaterThanOrEqualTo(400);
        for (ResponseEntity<Map> r : rs) {
            HttpHeaders h = r.getHeaders();
            assertThat(h.getCacheControl()).contains("no-store");
            assertThat(h.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(h.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
            assertThat(h.getFirst("Strict-Transport-Security")).startsWith("max-age=31536000");
            assertThat(h.getFirst("Content-Security-Policy")).isEqualTo("default-src 'none'; frame-ancestors 'none'");
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void unhandledExceptionIs500ProblemWithTraceIdAndNoInternals() throws Exception {
        GlobalExceptionHandler h = new GlobalExceptionHandler();
        ResponseEntity<org.springframework.http.ProblemDetail> r =
                h.handleUnexpected(new IllegalStateException("select * from patients where name='Asha'"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(r.getBody().getProperties()).containsKey("traceId");
        assertThat(String.valueOf(r.getBody().getDetail()) + r.getBody().getTitle()).doesNotContain("select").doesNotContain("Asha")
                .doesNotContain("Exception").doesNotContain("at com.");
        assertThat(r.getBody().getType().toString()).endsWith("/internal");
    }
}
