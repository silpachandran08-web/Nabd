package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.pharmacy.api.StockCommands;
import com.nabd.hms.pharmacy.api.StockCommands.BatchReceipt;
import com.nabd.hms.shared.events.consumer.ConsumerGroups;
import com.nabd.hms.shared.relay.OutboxRelay;
import com.nabd.hms.shared.relay.RelayProperties;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ClinicianFixture;
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
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHL-012 AC1 timing: doctor signs -> outbox -> relay (production class, NOTIFY-driven) -> Valkey stream -> pharmacy-worklist group
 * (row) -> outbox -> relay -> sse-fanout group -> every open pharmacist's SSE stream, against real Postgres, Redis 6.2 (the Valkey
 * stand-in) and HTTP. Consumer groups are polled here in a tight loop (the shipped background poll is every 200 ms).
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Phl012WorklistLiveApiTest extends ApiTestBase {

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
    @Autowired StockCommands stock;

    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Stream> open = new CopyOnWriteArrayList<>();
    private OutboxRelay relay;

    @AfterEach
    void close() {
        open.forEach(Stream::close);
        if (relay != null) relay.stop();
    }

    private static String phone() {
        return "+9196" + String.format("%08d", SEQ.incrementAndGet());
    }

    @Test
    void aSignedRxReachesEveryOpenPharmacistScreenWithinFiveSecondsP95() throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff doctor = ClinicianFixture.licensed(seedStaff(t, seedFullAccessRole(t.id()), "lv" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        String token = loginAndGetAccessToken(doctor);
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "in_house", "reason", "PHL-012 live")),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> item = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(token, Map.of("name", "Live drug", "isRx", false,
                "price", 10.00, "taxRatePercent", 12, "scheduleClass", "OTC")), Map.class);
        UUID itemId = UUID.fromString((String) item.getBody().get("id"));
        inTenantTx(t.id(), () -> stock.receiveBatch(itemId, new BatchReceipt("LV1", LocalDate.now().plusDays(300), new BigDecimal("12.00"),
                new BigDecimal("7.50"), 500, null, null, null), doctor.id(), "GRN"));
        UUID role = seedRole(t.id(), "Pharmacist", false, new ModuleGrant("pharmacy", true, true, true, false, false, false, false));
        String asha = loginAndGetAccessToken(seedStaff(t, role, "lva" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        String bilal = loginAndGetAccessToken(seedStaff(t, role, "lvb" + SEQ.incrementAndGet() + "@q.com", phone(), false));

        UUID viewOnly = seedRole(t.id(), "Viewer", false, new ModuleGrant("pharmacy", true, false, false, false, false, false, false));
        String viewer = loginAndGetAccessToken(seedStaff(t, viewOnly, "lvv" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        assertThat(client.send(request(viewer), HttpResponse.BodyHandlers.discarding()).statusCode()).as("topic needs pharmacy:dispense").isEqualTo(403);

        Stream a = connect(asha), b = connect(bilal);
        startRelay();

        List<Long> samples = new ArrayList<>();
        String lastEntry = null;
        for (int run = 0; run < 5; run++) {
            String qe = visit(token, "Live Patient " + run, doctor.id());
            exchange("/v1/clinical/prescriptions/" + qe, HttpMethod.PATCH, authedJsonBody(token, Map.of("items", List.of(Map.of("drugName", "Live drug",
                    "dosage", "1 tab", "frequency", "BD", "quantity", 2)))), Map.class);
            long t0 = System.nanoTime();
            ResponseEntity<Map> signed = exchange("/v1/clinical/prescriptions/" + qe + "/sign", HttpMethod.POST, authed(token), Map.class);
            assertThat(signed.getStatusCode()).as("sign %s", signed.getBody()).isEqualTo(HttpStatus.OK);
            String rx = (String) signed.getBody().get("id");
            Predicate<JsonNode> created = d -> d.path("type").asString().equals("pharmacy.worklist.entry_created.v1")
                    && d.path("fields").path("prescriptionId").asString().equals(rx);
            JsonNode[] got = await(created, a, b);
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertThat(got).as("run %d: a delta on both pharmacists' screens within %d ms", run, BUDGET_MS).doesNotContainNull();
            samples.add(ms);
            assertThat(got[0].path("topic").asString()).isEqualTo("pharmacy.worklist");
            assertThat(got[0].toString()).doesNotContain("Live Patient").doesNotContain("Live drug").doesNotContain("tenantId");
            lastEntry = got[0].path("id").asString();
            // the row is there for the refetch the client does on the delta
            List<Map<String, Object>> items = (List<Map<String, Object>>) exchange("/v1/pharmacy/worklist", HttpMethod.GET, authed(asha), Map.class)
                    .getBody().get("items");
            assertThat(items).extracting(e -> e.get("id")).contains(lastEntry);
        }
        List<Long> sorted = samples.stream().sorted().toList();
        long p95 = sorted.get((int) Math.ceil(0.95 * sorted.size()) - 1);
        System.out.println("PHL-012 sign-to-screen ms " + samples + " p95=" + p95);
        assertThat(p95).as("p95 sign-to-visible").isLessThan(BUDGET_MS);

        // a claim reaches the other pharmacist's screen as a status change
        String entry = lastEntry;
        assertThat(exchange("/v1/pharmacy/worklist/" + entry + "/claim", HttpMethod.POST, authedJsonBody(asha, Map.of()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JsonNode[] status = await(d -> d.path("type").asString().equals("pharmacy.worklist.status_changed.v1") && d.path("id").asString().equals(entry), b);
        assertThat(status[0]).as("status change on the other screen").isNotNull();
        assertThat(status[0].path("fields").path("status").asString()).isEqualTo("in_progress");
    }

    // ---- helpers ----

    private void startRelay() {
        relay = new OutboxRelay(new JdbcTemplate(jdbc.getDataSource()), valkey, new RelayProperties(true, shipped.batchSize(), shipped.leaseSeconds(),
                shipped.pollMs(), shipped.lockRetryMs(), shipped.errorBackoffMs(), shipped.streamMaxlen(), null, null, null),
                OutboxRelay.direct(env.getProperty("spring.datasource.url"), env.getProperty("spring.datasource.username"),
                        env.getProperty("spring.datasource.password")), new SimpleMeterRegistry());
        relay.start();
        long end = System.currentTimeMillis() + 15_000;
        while (!relay.isLeader() && System.currentTimeMillis() < end) {
            sleep(20);
        }
        assertThat(relay.isLeader()).as("relay leader").isTrue();
    }

    private String visit(String token, String name, UUID doctorId) {
        String patient = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of("name", name, "phone", phone(),
                "dob", LocalDate.of(1970, 1, 1).plusDays(SEQ.get() % 9000).toString(), "gender", "male")), Map.class).getBody().get("id");
        return (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of("patientId", patient,
                "doctorId", doctorId.toString())), Map.class).getBody().get("id");
    }

    /** Polls the consumer groups until every stream has a matching delta or the budget is spent; a missing one is null. */
    private JsonNode[] await(Predicate<JsonNode> match, Stream... streams) {
        JsonNode[] out = new JsonNode[streams.length];
        long end = System.currentTimeMillis() + BUDGET_MS;
        while (System.currentTimeMillis() < end) {
            groups.pollOnce();
            boolean all = true;
            for (int i = 0; i < streams.length; i++) {
                if (out[i] == null) out[i] = streams[i].find(match);
                all &= out[i] != null;
            }
            if (all) break;
            sleep(10);
        }
        return out;
    }

    private HttpRequest request(String token) {
        return HttpRequest.newBuilder(URI.create(url("/v1/events?topics=pharmacy.worklist"))).header("Authorization", "Bearer " + token)
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

        JsonNode find(Predicate<JsonNode> match) {
            return deltas.stream().filter(match).findFirst().orElse(null);
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
