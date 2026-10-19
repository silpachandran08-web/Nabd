package com.nabd.hms.shared.ops;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** OPS-025: readiness/metrics on the management port, nothing on the API port, trace id in Problem+JSON. */
class Ops025ObservabilityApiTest extends ApiTestBase {

    @LocalManagementPort
    private int mgmtPort;
    @org.springframework.boot.test.web.server.LocalServerPort
    private int apiPort;
    @Autowired
    private OutboxLag lag;
    @Autowired
    private Environment env;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void freshHeartbeat() {
        lag.polled(0, 0);
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void readinessIsUpWithDbAndOutboxContributors() throws Exception {
        var r = get(mgmtPort, "/actuator/health/readiness");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"status\":\"UP\"").contains("\"db\"").contains("\"outbox\"");
        assertThat(get(mgmtPort, "/actuator/health/liveness").statusCode()).isEqualTo(200);
    }

    @Test
    void readinessIsDownWhenOutboxDispatcherIsStale() throws Exception {
        lag.polledAt(Instant.now().minusSeconds(OutboxLag.MAX_AGE_SECONDS + 5));
        var r = get(mgmtPort, "/actuator/health/readiness");
        assertThat(r.statusCode()).isEqualTo(503);
        assertThat(r.body()).contains("\"status\":\"DOWN\"");
        assertThat(get(mgmtPort, "/actuator/health/liveness").statusCode()).isEqualTo(200); // stale outbox must not restart the pod
    }

    @Test
    void readinessGroupIncludesDbAndOutbox() {
        assertThat(env.getProperty("management.endpoint.health.group.readiness.include")).contains("db").contains("outbox");
    }

    @Test
    void prometheusExposesRedPoolJvmAndOutboxMetricsWithoutIdentifiers() throws Exception {
        get(apiPort, "/v1/does-not-exist"); // at least one request so http_server_requests exists
        var r = get(mgmtPort, "/actuator/prometheus");
        assertThat(r.statusCode()).isEqualTo(200);
        String body = r.body();
        assertThat(body).contains("http_server_requests_seconds_count", "hikaricp_connections_active", "hikaricp_connections_max",
                "jvm_memory_used_bytes", "outbox_events_claimed_total", "outbox_last_poll_age_seconds");
        assertThat(body).doesNotContainPattern("(?i)(tenant|patient|user|staff)[_a-z]*=\"");
        assertThat(body).doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void actuatorIsNotReachableOnTheApiPort() throws Exception {
        for (String p : new String[]{"/actuator/health", "/actuator/prometheus", "/actuator/health/readiness"}) {
            assertThat(get(apiPort, p).statusCode()).isIn(401, 403, 404);
        }
        assertThat(get(mgmtPort, "/v1/auth/login").statusCode()).isEqualTo(404); // and the API is not on the management port
        // only health and prometheus are exposed; an unexposed path is outside the permitAll actuator chain so the main chain answers 401 (never 200)
        assertThat(get(mgmtPort, "/actuator/env").statusCode()).isIn(401, 404);
    }

    @Test
    void problemJsonCarriesTheCallersTraceId() throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + apiPort + "/v1/auth/login"))
                .header("Content-Type", "application/json")
                .header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        var r = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isBetween(400, 499);
        assertThat(r.body()).contains("\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\"");
        assertThat(r.headers().firstValue("X-Request-Id")).contains("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void shutdownIsGracefulWithin25sAndFlywayIsOff() {
        assertThat(env.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(env.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("25s"); // OPS-053 raised OPS-025's 20s to 25s
        assertThat(env.getProperty("spring.flyway.enabled")).isEqualTo("false");
    }
}
