package com.nabd.hms.lab;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.shared.events.consumer.ConsumerGroups;
import com.nabd.hms.shared.relay.OutboxRelay;
import com.nabd.hms.shared.relay.RelayProperties;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ValkeyTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHL-043 AC2 (this story's half): a STAT order reaches an open lab screen within 5 s: order -> outbox -> relay (production class)
 * -> Valkey stream -> sse-fanout group -> the `lab.worklist` SSE topic, against real Postgres, Redis 6.2 (the Valkey stand-in) and
 * HTTP, as Phl012WorklistLiveApiTest does for the pharmacy worklist. The worklist screen itself is PHL-044.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Phl043LabOrderLiveApiTest extends ApiTestBase {

    static final ValkeyTestSupport VALKEY = ValkeyTestSupport.start();
    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000) + 100_000);
    private static final long BUDGET_MS = 5_000;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.valkey.url", VALKEY::url);
        r.add("app.events.consumer.enabled", () -> "false");
    }

    @AfterAll
    static void stopValkey() {
        VALKEY.close();
    }

    @Autowired ConsumerGroups groups;
    @Autowired StringRedisTemplate valkey;
    @Autowired Environment env;
    @Autowired RelayProperties shipped;

    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Stream> open = new CopyOnWriteArrayList<>();
    private OutboxRelay relay;

    @AfterEach
    void close() {
        open.forEach(Stream::close);
        if (relay != null) relay.stop();
    }

    private static String phone() {
        return "+9195" + String.format("%08d", SEQ.incrementAndGet());
    }

    @Test
    void aStatOrderReachesAnOpenLabScreenWithinFiveSeconds_idsOnly() throws Exception {
        SeededTenant t = seedTenant();
        ModuleGrant[] grants = java.util.stream.Stream.of("patients", "queue", "clinical", "billing", "setup", "departments", "lab").map(ApiTestBase::fullGrant)
                .toArray(ModuleGrant[]::new);
        SeededStaff doctor = seedStaff(t, seedRole(t.id(), "Owner", true, grants), "lo" + SEQ.incrementAndGet() + "@p43.test", phone(), false);
        String token = loginAndGetAccessToken(doctor);
        ResponseEntity<Map> test = exchange("/v1/lab/tests", HttpMethod.POST, authedJsonBody(token, Map.of("code", "TROP", "name", "Troponin I", "sampleType", "serum",
                "container", "sst_gold", "volumeMl", 3, "unit", "ng/L", "tatMinutes", 60, "price", 900)), Map.class);
        assertThat(test.getStatusCode()).as("%s", test.getBody()).isEqualTo(HttpStatus.CREATED);
        String patient = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of("name", "Live Lab Person", "phone", phone(),
                "dob", LocalDate.of(1975, 5, 5).toString(), "gender", "male")), Map.class).getBody().get("id");
        String entry = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of("patientId", patient,
                "doctorId", doctor.id().toString())), Map.class).getBody().get("id");

        UUID techRole = seedRole(t.id(), "Lab technician", false, new ModuleGrant("lab", true, false, true, false, false, false, false));
        String tech = loginAndGetAccessToken(seedStaff(t, techRole, "lt" + SEQ.incrementAndGet() + "@p43.test", phone(), false));
        UUID deskRole = seedRole(t.id(), "Desk", false, new ModuleGrant("queue", true, true, false, false, false, false, false));
        String desk = loginAndGetAccessToken(seedStaff(t, deskRole, "ld" + SEQ.incrementAndGet() + "@p43.test", phone(), false));
        assertThat(client.send(request(desk), HttpResponse.BodyHandlers.discarding()).statusCode()).as("the topic needs lab:view").isEqualTo(403);

        Stream screen = connect(tech);
        startRelay();

        long t0 = System.nanoTime();
        ResponseEntity<Map> placed = exchange("/v1/lab/orders", HttpMethod.POST, authedJsonBody(token, Map.of("patientId", patient, "queueEntryId", entry,
                "items", List.of(Map.of("testId", test.getBody().get("id"))), "priority", "stat", "destination", Map.of("kind", "inhouse"))), Map.class);
        assertThat(placed.getStatusCode()).as("%s", placed.getBody()).isEqualTo(HttpStatus.CREATED);
        String orderId = (String) placed.getBody().get("orderId");

        JsonNode delta = null;
        long end = System.currentTimeMillis() + BUDGET_MS;
        while (delta == null && System.currentTimeMillis() < end) {
            groups.pollOnce();
            delta = screen.deltas.stream().filter(d -> d.path("type").asString().equals("lab.order.placed.v1") && d.path("id").asString().equals(orderId))
                    .findFirst().orElse(null);
            if (delta == null) sleep(10);
        }
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        System.out.println("PHL-043 STAT order-to-screen ms " + ms);
        assertThat(delta).as("a delta on the open lab screen within %d ms", BUDGET_MS).isNotNull();
        assertThat(delta.path("topic").asString()).isEqualTo("lab.worklist");
        assertThat(delta.path("fields").path("priority").asString()).isEqualTo("stat");
        assertThat(delta.toString()).doesNotContain("Live Lab Person").doesNotContain("Troponin").doesNotContain(patient).doesNotContain("tenantId");
        // the row the screen refetches is there, STAT
        List<Map<String, Object>> rows = (List<Map<String, Object>>) exchange("/v1/lab/orders?queueEntryId=" + entry, HttpMethod.GET, authed(token), Map.class)
                .getBody().get("items");
        assertThat(rows).singleElement().satisfies(o -> assertThat(o).containsEntry("id", orderId).containsEntry("priority", "stat"));
    }

    // ---- helpers (as Phl012WorklistLiveApiTest) ----

    private void startRelay() {
        relay = new OutboxRelay(new JdbcTemplate(jdbc.getDataSource()), valkey, new RelayProperties(true, shipped.batchSize(), shipped.leaseSeconds(),
                shipped.pollMs(), shipped.lockRetryMs(), shipped.errorBackoffMs(), shipped.streamMaxlen(), null, null, null),
                OutboxRelay.direct(env.getProperty("spring.datasource.url"), env.getProperty("spring.datasource.username"),
                        env.getProperty("spring.datasource.password")), new SimpleMeterRegistry());
        relay.start();
        long end = System.currentTimeMillis() + 15_000;
        while (!relay.isLeader() && System.currentTimeMillis() < end) sleep(20);
        assertThat(relay.isLeader()).as("relay leader").isTrue();
    }

    private HttpRequest request(String token) {
        return HttpRequest.newBuilder(URI.create(url("/v1/events?topics=lab.worklist"))).header("Authorization", "Bearer " + token)
                .header("Accept", "text/event-stream").build();
    }

    private Stream connect(String token) throws Exception {
        HttpResponse<InputStream> r = client.send(request(token), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(r.statusCode()).isEqualTo(200);
        Stream s = new Stream(r.body());
        open.add(s);
        long end = System.currentTimeMillis() + 3000;
        while (!s.snapshot && System.currentTimeMillis() < end) sleep(10);
        assertThat(s.snapshot).as("snapshot").isTrue();
        sleep(300); // the pod's channel subscription is asynchronous
        return s;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    final class Stream {
        final List<JsonNode> deltas = new CopyOnWriteArrayList<>();
        final InputStream in;
        volatile boolean snapshot;

        Stream(InputStream in) {
            this.in = in;
            Thread.ofVirtual().start(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String event = null, line;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith("event:")) event = line.substring(6).trim();
                        else if (line.startsWith("data:") && "snapshot".equals(event)) snapshot = true;
                        else if (line.startsWith("data:") && "delta".equals(event)) deltas.add(objectMapper.readTree(line.substring(5).trim()));
                    }
                } catch (IOException ignored) {
                    // closed by the test
                }
            });
        }

        void close() {
            try {
                in.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }
}
