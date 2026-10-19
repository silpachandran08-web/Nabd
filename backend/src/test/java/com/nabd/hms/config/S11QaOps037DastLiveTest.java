package com.nabd.hms.config;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Independent QA (S11 OPS-037 AC4/AC5): the API that previews will run, checked directly and with the very script the
 * dast.yml headers-cors job runs (scripts/ci/dast.py headers), so the CI gate is proven against the real server.
 */
class S11QaOps037DastLiveTest extends ApiTestBase {

    private static final String STAFF_ORIGIN = "http://localhost:3000";

    private String ownerToken() {
        SeededTenant t = seedTenant();
        return loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "s11qa37" + UUID.randomUUID().toString().substring(0, 6) + "@q.com",
                "+91940071" + String.format("%04d", (int) (Math.random() * 9000)), false));
    }

    private ResponseEntity<String> preflight(String origin, String verb) {
        HttpHeaders h = new HttpHeaders();
        h.set("Origin", origin);
        h.set("Access-Control-Request-Method", verb);
        h.set("Access-Control-Request-Headers", "authorization,content-type,idempotency-key");
        return http.exchange(url("/v1/appointments/00000000-0000-0000-0000-000000000001"), HttpMethod.OPTIONS, new HttpEntity<>(h), String.class);
    }

    @Test
    void ac4_limitZeroIs400ProblemJsonNever500_andBoundary100IsOk() {
        String tok = ownerToken();
        for (String bad : new String[]{"0", "-1", "101"}) {
            ResponseEntity<String> r = exchange("/v1/appointments?limit=" + bad, HttpMethod.GET, authed(tok), String.class);
            assertThat(r.getStatusCode()).as("limit=" + bad).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(String.valueOf(r.getHeaders().getContentType())).contains("application/problem+json");
        }
        assertThat(exchange("/v1/appointments?limit=100", HttpMethod.GET, authed(tok), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/appointments?limit=1", HttpMethod.GET, authed(tok), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void ac5_putPreflightFromStaffOriginIsAllowed_foreignOriginIsNot() {
        ResponseEntity<String> ok = preflight(STAFF_ORIGIN, "PUT");
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getHeaders().getFirst("Access-Control-Allow-Methods")).contains("PUT");
        assertThat(ok.getHeaders().getFirst("Access-Control-Allow-Origin")).isEqualTo(STAFF_ORIGIN);
        ResponseEntity<String> evil = preflight("https://evil.example.com", "PUT");
        assertThat(evil.getHeaders().getFirst("Access-Control-Allow-Origin")).isNull();
    }

    private Map.Entry<Integer, String> dast(String staffOrigin, String token) throws Exception {
        String base = "http://localhost:" + port;
        ProcessBuilder pb = new ProcessBuilder("python3", "scripts/ci/dast.py", "headers", base + "/v1/auth/login", base)
                .directory(new File("..")).redirectErrorStream(true);
        pb.environment().put("STAFF_ORIGIN", staffOrigin);
        if (token != null) pb.environment().put("DAST_TOKEN", token);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return Map.entry(p.waitFor(), out);
    }

    @Test
    void ciHeadersGatePassesAgainstTheRealApi() throws Exception {
        Map.Entry<Integer, String> r = dast(STAFF_ORIGIN, ownerToken());
        assertThat(r.getValue()).contains("headers/CORS: pass");
        assertThat(r.getKey()).isZero();
    }

    @Test
    void ciHeadersGateBitesWhenTheOriginIsNotTheStaffOrigin() throws Exception {
        Map.Entry<Integer, String> r = dast("https://not-staff.example.com", null);
        assertThat(r.getKey()).isEqualTo(1);
        assertThat(r.getValue()).contains("CORS preflight PUT from staff origin refused");
    }
}
