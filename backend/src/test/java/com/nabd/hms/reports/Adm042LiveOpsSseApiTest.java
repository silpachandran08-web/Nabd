package com.nabd.hms.reports;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.realtime.RealtimeHub;
import com.nabd.hms.realtime.RealtimeTopic;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADM-042 "events -> projection -> SSE": the shipped alert pass (OpsAlertJob, every 10 s, not triggered by the test) -> outbox ->
 * relay (production class) -> Valkey stream -> sse-fanout group -> the owner's open 'ops' stream, against real Postgres, Redis 6.2
 * (the Valkey stand-in) and HTTP. Consumer groups are polled here in a tight loop (the shipped background poll is every 200 ms).
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Adm042LiveOpsSseApiTest extends ApiTestBase {

    static final ValkeyTestSupport VALKEY = ValkeyTestSupport.start();
    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000) + 500_000);
    private static final long BUDGET_MS = 15_000;
    private static final Predicate<JsonNode> PHARMACY_ALERT = d -> d.path("type").asString().equals("reporting.ops_alert.raised.v1")
            && d.path("fields").path("code").asString().equals("pharmacy_wait");

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
    @Autowired RealtimeHub hub;
    @Autowired LiveOpsService service;
    @Autowired OpsAlertJob job;

    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Stream> open = new CopyOnWriteArrayList<>();
    private OutboxRelay relay;

    @AfterEach
    void close() {
        open.forEach(Stream::close);
        if (relay != null) relay.stop();
    }

    private static String phone() {
        return "+9194" + String.format("%08d", SEQ.incrementAndGet());
    }

    private String owner(SeededTenant t) {
        return loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "a42s-" + SEQ.incrementAndGet() + "@a.com", phone(), false));
    }

    @Test
    void theShippedPassRunsWellInsideFifteenSeconds() throws Exception {
        String delay = OpsAlertJob.class.getMethod("scheduled").getAnnotation(Scheduled.class).fixedDelayString();
        assertThat(delay).isEqualTo("${app.ops-alerts.interval-ms:10000}");
    }

    @Test
    void aPharmacyWaitOverTheTargetReachesTheOpenBoardWithinFifteenSecondsOnceAndFollowsTheEditedTarget() throws Exception {
        SeededTenant a = seedTenant(), b = seedTenant();
        String tokenA = owner(a), tokenB = owner(b);
        String desk = loginAndGetAccessToken(seedStaff(a, seedRole(a.id(), "Desk", false, new ModuleGrant("queue", true, true, true, false, false, false, false)),
                "a42q-" + SEQ.incrementAndGet() + "@a.com", phone(), false));
        assertThat(client.send(request(desk), HttpResponse.BodyHandlers.discarding()).statusCode()).as("the ops topic needs reports:view").isEqualTo(403);
        assertThat(hub.tenantsWatching(RealtimeTopic.OPS)).as("nobody has a board open yet").doesNotContain(a.id(), b.id());

        UUID entry = inTenantTx(a.id(), () -> jdbc.queryForObject("INSERT INTO pharmacy_worklist_entries (tenant_id, prescription_id, patient_id, signed_at, lines, created_at) "
                + "VALUES (?, gen_random_uuid(), gen_random_uuid(), now(), '[]'::jsonb, now() - interval '7 minutes') RETURNING id", UUID.class, a.id()));
        Stream boardA = connect(tokenA), boardB = connect(tokenB);
        assertThat(hub.tenantsWatching(RealtimeTopic.OPS)).contains(a.id(), b.id());
        startRelay();
        assertThat(service.evaluate(a.id())).as("7 min is under the 8 min target").isZero();

        // AC2: the wait goes over 8 minutes; the board is told by the shipped pass, within 15 s
        long t0 = System.nanoTime();
        inTenantTx(a.id(), () -> jdbc.update("UPDATE pharmacy_worklist_entries SET created_at = now() - interval '9 minutes 5 seconds' WHERE id = ?", entry));
        JsonNode delta = await(PHARMACY_ALERT, boardA, 1);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        System.out.println("ADM-042 wait-over-target to screen ms " + ms);
        assertThat(delta).as("an alert delta on the open board within %d ms", BUDGET_MS).isNotNull();
        assertThat(ms).isLessThan(BUDGET_MS);
        assertThat(delta.path("topic").asString()).isEqualTo("ops");
        assertThat(delta.path("id").asString()).isEqualTo("pharmacy_wait");
        assertThat(delta.path("fields").path("severity").asString()).isEqualTo("warning");
        assertThat(delta.path("fields").properties().stream().map(Map.Entry::getKey)).as("a code and a severity, nothing else").containsExactlyInAnyOrder("code", "severity");
        assertThat(delta.toString()).doesNotContain("tenantId");
        // the row is there for the re-read the screen does on the delta
        List<Map> alerts = (List<Map>) exchange("/v1/reports/live", HttpMethod.GET, authed(tokenA), Map.class).getBody().get("alerts");
        assertThat(alerts).extracting(x -> x.get("code")).containsExactly("pharmacy_wait");

        // raised once while it stays active, and never on another clinic's board
        assertThat(service.evaluate(a.id())).isZero();
        job.scheduled();
        drain();
        assertThat(boardA.count(PHARMACY_ALERT)).isEqualTo(1);
        assertThat(boardB.deltas).as("clinic B's board").isEmpty();
        assertThat(valkey.opsForSet().members("t:" + a.id() + ":ops:alerts")).containsExactly("pharmacy_wait");

        // AC4: the owner sets the target to 10 minutes; the next pass uses it (9 min no longer alerts), and 11 min alerts again
        assertThat(exchange("/v1/reports/live/thresholds", HttpMethod.PUT, authedJsonBody(tokenA, Map.of("pharmacyWaitMin", 10, "labTatBreach", true,
                "requestWaitMin", 10, "doctorLateMin", 15)), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(service.evaluate(a.id())).isZero();
        assertThat(valkey.opsForSet().members("t:" + a.id() + ":ops:alerts")).as("cleared with the new target").isEmpty();
        inTenantTx(a.id(), () -> jdbc.update("UPDATE pharmacy_worklist_entries SET created_at = now() - interval '11 minutes 5 seconds' WHERE id = ?", entry));
        service.evaluate(a.id()); // or the shipped pass, whichever runs first: SADD lets exactly one of them raise it
        assertThat(await(PHARMACY_ALERT, boardA, 2)).as("over the new 10 min target: raised again, on the board").isNotNull();
        drain();
        assertThat(boardA.count(PHARMACY_ALERT)).isEqualTo(2);
        assertThat(boardB.deltas).isEmpty();
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

    /** Polls the consumer groups until the stream holds {@code n} matching deltas or the budget is spent; returns the n-th or null. */
    private JsonNode await(Predicate<JsonNode> match, Stream s, int n) {
        long end = System.currentTimeMillis() + BUDGET_MS;
        while (System.currentTimeMillis() < end) {
            groups.pollOnce();
            if (s.count(match) >= n) {
                return s.deltas.stream().filter(match).skip(n - 1).findFirst().orElse(null);
            }
            sleep(10);
        }
        return null;
    }

    /** Lets anything still in the outbox or the streams reach the boards. */
    private void drain() {
        long end = System.currentTimeMillis() + 1500;
        while (System.currentTimeMillis() < end) {
            groups.pollOnce();
            sleep(20);
        }
    }

    private HttpRequest request(String token) {
        return HttpRequest.newBuilder(URI.create(url("/v1/events?topics=ops"))).header("Authorization", "Bearer " + token)
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

        long count(Predicate<JsonNode> match) {
            return deltas.stream().filter(match).count();
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
