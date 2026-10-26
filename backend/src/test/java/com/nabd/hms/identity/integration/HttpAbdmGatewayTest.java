package com.nabd.hms.identity.integration;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.shared.integration.ExternalCallGuard;
import com.nabd.hms.shared.integration.IntegrationProperties;
import com.nabd.hms.shared.integration.ResilientHttpClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IDC-016 contract test against a local stand-in for the ABDM gateway that replays a fixture of the v3 session exchange
 * (POST sessions -> accessToken/expiresIn) and a registry lookup (200 found, 404 not found). It is NOT a recording of the
 * real sandbox: that run is Needs external action (DEP-05). It proves the client's own behaviour: headers, token reuse,
 * one retry on 401, timeouts, circuit breaker, fail-closed configuration.
 */
class HttpAbdmGatewayTest {

    private HttpServer server;
    private final List<Map<String, String>> sessionHeaders = new CopyOnWriteArrayList<>();
    private final AtomicInteger sessions = new AtomicInteger();
    private final AtomicInteger lookups = new AtomicInteger();
    private volatile int lookupStatus = 200;
    private volatile int firstLookup401 = 0;
    private volatile long lookupDelayMs = 0;
    private final List<String> lookupAuth = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/hiecm/gateway/v3/sessions", ex -> {
            sessions.incrementAndGet();
            sessionHeaders.add(Map.of("req", String.valueOf(ex.getRequestHeaders().getFirst("REQUEST-ID")), "cm", String.valueOf(ex.getRequestHeaders().getFirst("X-CM-ID")),
                    "ts", String.valueOf(ex.getRequestHeaders().getFirst("TIMESTAMP"))));
            byte[] b = ("{\"accessToken\":\"tok" + sessions.get() + "\",\"expiresIn\":1200,\"tokenType\":\"Bearer\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/registry/", ex -> {
            int n = lookups.incrementAndGet();
            lookupAuth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            if (lookupDelayMs > 0) {
                try { Thread.sleep(lookupDelayMs); } catch (InterruptedException ignored) { }
            }
            int status = n <= firstLookup401 ? 401 : lookupStatus;
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpAbdmGateway gateway(String base, String hfr, Optional<String> id, Optional<String> secret, int threshold) {
        AbdmProperties props = new AbdmProperties(base, null, null, hfr, hfr, "", null, null);
        IntegrationProperties ip = new IntegrationProperties(500, 400, threshold, 30, Map.of());
        ResilientHttpClient http = new ResilientHttpClient(ip, new ExternalCallGuard(new MockEnvironment()), Clock.systemUTC());
        return new HttpAbdmGateway(props, http, Clock.systemUTC(), name -> name.equals("abdm-client-id") ? id : secret);
    }

    private HttpAbdmGateway ok() {
        return gateway("http://127.0.0.1:" + server.getAddress().getPort(), "/registry/{id}", Optional.of("cid"), Optional.of("csecret"), 5);
    }

    @Test
    void sessionIsFetchedOnceWithGatewayHeadersAndReusedAcrossLookups() {
        HttpAbdmGateway g = ok();
        assertThat(g.facilityExists("HFR-1")).isTrue();
        assertThat(g.professionalExists("hpr@abdm")).isTrue();
        assertThat(sessions.get()).isEqualTo(1);
        assertThat(lookupAuth).containsOnly("Bearer tok1");
        assertThat(sessionHeaders.get(0)).containsEntry("cm", "sbx");
        assertThat(sessionHeaders.get(0).get("req")).matches("[0-9a-f-]{36}");
        assertThat(sessionHeaders.get(0).get("ts")).endsWith("Z");
    }

    @Test
    void notFoundIsFalseNotAnError() {
        lookupStatus = 404;
        assertThat(ok().facilityExists("NOPE")).isFalse();
    }

    @Test
    void a401DropsTheTokenAndRetriesOnceWithAFreshSession() {
        firstLookup401 = 1;
        assertThat(ok().facilityExists("HFR-1")).isTrue();
        assertThat(sessions.get()).isEqualTo(2);
        assertThat(lookupAuth).containsExactly("Bearer tok1", "Bearer tok2");
    }

    @Test
    void aPersistent401IsUnavailableNotAnInfiniteLoop() {
        firstLookup401 = 99;
        assertThatThrownBy(() -> ok().facilityExists("HFR-1")).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(503));
        assertThat(lookups.get()).isEqualTo(2);
    }

    @Test
    void aSlowRegistryTimesOutAndRepeatedFailuresOpenTheCircuit() {
        lookupDelayMs = 1500; // read timeout is 400 ms
        HttpAbdmGateway g = gateway("http://127.0.0.1:" + server.getAddress().getPort(), "/registry/{id}", Optional.of("cid"), Optional.of("csecret"), 2);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> g.facilityExists("HFR-1")).isInstanceOf(ApiException.class).hasMessageNotContaining("tok");
        }
        int before = lookups.get();
        assertThatThrownBy(() -> g.facilityExists("HFR-1")).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(503));
        assertThat(lookups.get()).as("circuit open: the third call never reaches the registry").isEqualTo(before);
    }

    @Test
    void unconfiguredIsAClosed503WithNoNetworkCall() {
        for (HttpAbdmGateway g : List.of(
                gateway("", "/registry/{id}", Optional.of("cid"), Optional.of("csecret"), 5),
                gateway("http://127.0.0.1:" + server.getAddress().getPort(), "", Optional.of("cid"), Optional.of("csecret"), 5),
                gateway("http://127.0.0.1:" + server.getAddress().getPort(), "/registry/{id}", Optional.empty(), Optional.of("csecret"), 5))) {
            assertThatThrownBy(() -> g.facilityExists("HFR-1")).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(503));
        }
        assertThat(sessions.get() + lookups.get()).isZero();
    }
}
