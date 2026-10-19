package com.nabd.hms.shared.ops;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA (S10) for OPS-025 (the parts checkable inside the JVM; image/OTel/SIGTERM items are in the report as NOT-RUNNABLE). */
class S10QaOps025ObservabilityTest extends ApiTestBase {

    @LocalManagementPort int mgmt;
    @LocalServerPort int api;
    @Autowired OutboxLag lag;
    @Autowired Environment env;
    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> get(int port, String path, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(int port, String path, String json, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ---- AC1: readiness ----

    @Test
    void ac1_readinessUpWithDbAndOutbox_noAuthNeeded_livenessUp() throws Exception {
        lag.polled(0, 0);
        var r = get(mgmt, "/actuator/health/readiness");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"status\":\"UP\"").contains("\"db\"").contains("\"outbox\"");
        assertThat(get(mgmt, "/actuator/health/liveness").statusCode()).isEqualTo(200);
    }

    @Test
    void ac1_outboxAge_119sIsUp_121sIsDown_thenRecovers() throws Exception {
        try {
            lag.polledAt(Instant.now().minusSeconds(OutboxLag.MAX_AGE_SECONDS - 2));
            assertThat(get(mgmt, "/actuator/health/readiness").statusCode()).as("118s old").isEqualTo(200);
            lag.polledAt(Instant.now().minusSeconds(OutboxLag.MAX_AGE_SECONDS + 5));
            var down = get(mgmt, "/actuator/health/readiness");
            assertThat(down.statusCode()).isEqualTo(503);
            assertThat(down.body()).contains("\"status\":\"DOWN\"");
            assertThat(get(mgmt, "/actuator/health/liveness").statusCode()).as("liveness unaffected by a stale outbox").isEqualTo(200);
        } finally {
            lag.polled(0, 0);
        }
        assertThat(get(mgmt, "/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    @Test
    void ac1_readinessIsDownWhenTheDatabaseCannotBeReached_andRecovers() throws Exception {
        try (Connection su = superuserConnection(); Statement st = su.createStatement()) {
            try {
                st.execute("ALTER ROLE " + APP_ROLE + " NOLOGIN");
                st.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = '" + APP_ROLE + "'");
                Thread.sleep(500);
                var r = get(mgmt, "/actuator/health/readiness");
                assertThat(r.statusCode()).as("readiness body: " + r.body()).isEqualTo(503);
                assertThat(r.body()).contains("\"status\":\"DOWN\"");
                assertThat(get(mgmt, "/actuator/health/liveness").statusCode()).as("liveness must stay UP so a DB blip does not restart pods").isEqualTo(200);
            } finally {
                st.execute("ALTER ROLE " + APP_ROLE + " LOGIN");
            }
        }
        long end = System.currentTimeMillis() + 20_000;
        int code = 0;
        while (System.currentTimeMillis() < end && (code = get(mgmt, "/actuator/health/readiness").statusCode()) != 200) Thread.sleep(500);
        assertThat(code).as("readiness recovers after DB is back").isEqualTo(200);
    }

    // ---- AC2: metrics ----

    @Test
    void ac2_prometheusHasRedPoolJvmOutboxMetrics_andLabelsCarryNoIdentifiers() throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "s10q25" + UUID.randomUUID() + "@a.com", "+919830" + (100000 + (int) (Math.random() * 800000)), false);
        String token = loginAndGetAccessToken(s);
        UUID randomPatient = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            get(api, "/v1/patients/" + randomPatient, "Authorization", "Bearer " + token); // 404 with a raw id in the path
            get(api, "/v1/no-such-endpoint/" + UUID.randomUUID() + "/" + t.slug());
        }
        String body = get(mgmt, "/actuator/prometheus").body();
        assertThat(body).contains("http_server_requests_seconds_count", "http_server_requests_seconds_sum",
                "hikaricp_connections_active", "hikaricp_connections_pending", "hikaricp_connections_max", "jvm_memory_used_bytes", "jvm_gc", "jvm_threads_live_threads",
                "outbox_events_claimed_total", "outbox_last_poll_age_seconds");
        assertThat(body).containsPattern("http_server_requests_seconds_count\\{[^}]*status=\"(404|200|401|400)\"");
        assertThat(body).doesNotContain(randomPatient.toString()).doesNotContain(t.id().toString()).doesNotContain(t.slug()).doesNotContain(s.email());
        assertThat(body).doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(body).doesNotContainPattern("(?i)\\b(tenant|patient|user|staff|email|phone)[_a-z]*=\"");
        assertThat(body).containsPattern("uri=\"(NOT_FOUND|/v1/patients/\\{[a-zA-Z]+\\})\""); // templated, not the raw path
    }

    @Test
    void ac2_unknownPathsDoNotExplodeTheUriCardinality() throws Exception {
        for (int i = 0; i < 25; i++) get(api, "/v1/zz-" + UUID.randomUUID());
        String body = get(mgmt, "/actuator/prometheus").body();
        long distinct = body.lines().filter(l -> l.startsWith("http_server_requests_seconds_count")).filter(l -> l.contains("zz-")).count();
        assertThat(distinct).isZero();
    }

    // ---- AC3: trace id in Problem+JSON ----

    @Test
    void ac3_traceIdFromTraceparentAppearsInProblemJson_andInTheResponseHeader() throws Exception {
        var r = post(api, "/v1/auth/login", "{}", "traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        assertThat(r.statusCode()).isBetween(400, 499);
        assertThat(r.body()).contains("\"traceId\":\"0af7651916cd43dd8448eb211c80319c\"");
        assertThat(r.headers().firstValue("X-Request-Id")).contains("0af7651916cd43dd8448eb211c80319c");
        assertThat(r.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
    }

    @Test
    void ac3_withoutTraceparentTheErrorStillCarriesAFreshTraceId_andTwoRequestsDiffer() throws Exception {
        var a = post(api, "/v1/auth/login", "{}");
        var b = post(api, "/v1/auth/login", "{}");
        var ma = java.util.regex.Pattern.compile("\"traceId\":\"([^\"]+)\"").matcher(a.body());
        var mb = java.util.regex.Pattern.compile("\"traceId\":\"([^\"]+)\"").matcher(b.body());
        assertThat(ma.find() && mb.find()).as(a.body()).isTrue();
        assertThat(ma.group(1)).isNotBlank().isNotEqualTo(mb.group(1));
    }

    @Test
    void ac3_malformedOrAllZeroTraceparentIsIgnored_notEchoedIntoTheBody() throws Exception {
        for (String bad : new String[]{"00-00000000000000000000000000000000-b7ad6b7169203331-01", "garbage\"}{injected", "00-0AF7651916CD43DD8448EB211C80319C-b7ad6b7169203331-01", "00-0af76519-b7ad6b7169203331-01"}) {
            var r = post(api, "/v1/auth/login", "{}", "traceparent", bad.contains("\"") ? "garbage-injected" : bad);
            assertThat(r.statusCode()).isBetween(400, 499);
            assertThat(r.body()).doesNotContain("0000000000000000000000000000000").doesNotContain("injected");
        }
    }

    @Test
    void ac3_traceIdOnAnAuthorisedAppError() throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "s10q25b" + UUID.randomUUID() + "@a.com", "+919831" + (100000 + (int) (Math.random() * 800000)), false);
        String token = loginAndGetAccessToken(s);
        var r = get(api, "/v1/patients/" + UUID.randomUUID(), "Authorization", "Bearer " + token, "traceparent", "00-11111111111111111111111111111111-2222222222222222-01");
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).contains("\"traceId\":\"11111111111111111111111111111111\"");
    }

    // ---- NFR: management port isolation ----

    @Test
    void nfr_actuatorOnlyOnManagementPort_andOnlyHealthAndPrometheus() throws Exception {
        for (String p : new String[]{"/actuator/health", "/actuator/health/readiness", "/actuator/prometheus", "/actuator/env", "/actuator/heapdump", "/actuator/loggers"})
            assertThat(get(api, p).statusCode()).as("api port " + p).isIn(401, 403, 404);
        for (String p : new String[]{"/actuator/env", "/actuator/heapdump", "/actuator/loggers", "/actuator/beans", "/actuator/threaddump"})
            assertThat(get(mgmt, p).statusCode()).as("mgmt " + p).isIn(401, 403, 404);
        assertThat(get(mgmt, "/actuator/prometheus").statusCode()).isEqualTo(200);
    }

    // ---- AC4/5/6: config-level checks (the image itself is NOT-RUNNABLE here) ----

    @Test
    @SuppressWarnings("unchecked")
    void ac5_ac6_applicationYmlHasFlywayOffGracefulShutdownAnd20sOrMorePhaseTimeout() throws Exception {
        Map<String, Object> y = new org.yaml.snakeyaml.Yaml().load(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/application.yml")));
        Map<String, Object> spring = (Map<String, Object>) y.get("spring");
        assertThat(((Map<String, Object>) spring.get("flyway")).get("enabled")).isEqualTo(false);
        assertThat(((Map<String, Object>) ((Map<String, Object>) y.get("server"))).get("shutdown")).isEqualTo("graceful");
        String phase = String.valueOf(((Map<String, Object>) spring.get("lifecycle")).get("timeout-per-shutdown-phase"));
        assertThat(Integer.parseInt(phase.replace("s", ""))).isGreaterThanOrEqualTo(20);
        assertThat(String.valueOf(((Map<String, Object>) ((Map<String, Object>) y.get("management")).get("server")).get("port"))).contains("8081");
    }

    @Test
    void ac5_theMigrateEntrypointRunsFlywayAsTheMigratorRoleOnAnEmptyDatabase() throws Exception {
        String db = "s10qa_mig_" + System.nanoTime();
        String base;
        try (Connection su = superuserConnection()) {
            base = su.getMetaData().getURL();
            try (Statement st = su.createStatement()) { st.execute("CREATE DATABASE " + db + " OWNER " + MIGRATOR_ROLE); }
        }
        try {
            String url = base.replaceFirst("/[^/?]+(\\?.*)?$", "/" + db + "$1");
            // roles are cluster-level and already exist; the migrator needs rights on the fresh database
            try (Connection su = java.sql.DriverManager.getConnection(url, superuserDataSource().getUsername(), superuserDataSource().getPassword()); Statement st = su.createStatement()) {
                st.execute("ALTER SCHEMA public OWNER TO " + MIGRATOR_ROLE);
            }
            int n = com.nabd.hms.common.MigrateMain.migrate(url, MIGRATOR_ROLE, MIGRATOR_ROLE_PW, "filesystem:../db/migrations");
            assertThat(n).isGreaterThan(50);
            // idempotent: a second run (second replica / re-deploy) applies nothing
            assertThat(com.nabd.hms.common.MigrateMain.migrate(url, MIGRATOR_ROLE, MIGRATOR_ROLE_PW, "filesystem:../db/migrations")).isZero();
        } finally {
            try (Connection su = superuserConnection(); Statement st = su.createStatement()) { st.execute("DROP DATABASE IF EXISTS " + db + " WITH (FORCE)"); }
        }
    }
}
