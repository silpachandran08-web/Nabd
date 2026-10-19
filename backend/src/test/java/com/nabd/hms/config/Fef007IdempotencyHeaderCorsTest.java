package com.nabd.hms.config;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** FEF-007: the shared client sends Idempotency-Key and X-Request-Id on mutations, so the browser preflight must allow them. */
class Fef007IdempotencyHeaderCorsTest extends ApiTestBase {

    private ResponseEntity<String> preflight(String origin, String requestHeaders) {
        HttpHeaders h = new HttpHeaders();
        h.set("Origin", origin);
        h.set("Access-Control-Request-Method", "POST");
        h.set("Access-Control-Request-Headers", requestHeaders);
        return http.exchange(url("/v1/queue/check-in"), HttpMethod.OPTIONS, new HttpEntity<>(h), String.class);
    }

    @Test
    void preflightAllowsIdempotencyKeyAndRequestId() {
        ResponseEntity<String> r = preflight("http://localhost:3000", "authorization,content-type,idempotency-key,x-request-id");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getFirst("Access-Control-Allow-Headers").toLowerCase()).contains("idempotency-key", "x-request-id");
    }

    @Test
    void foreignOriginStillRefusedWithIdempotencyKey() {
        assertThat(preflight("https://evil.example.com", "idempotency-key").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
