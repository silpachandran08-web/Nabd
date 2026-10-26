package com.nabd.hms.realtime;

import com.nabd.hms.shared.events.consumer.ConsumerGroups;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ValkeyTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ACC-021 against a real Redis 6.2 (the Valkey stand-in) and a real HTTP server. The relay is not here, so the tests XADD
 * CloudEvents to ev:in:* and drive the sse-fanout consumer with ConsumerGroups.pollOnce() (background polls are off).
 */
class RealtimeSseApiTest extends ApiTestBase {

    static final ValkeyTestSupport VALKEY = ValkeyTestSupport.start();
    static final AtomicLong IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L * 10);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.valkey.url", VALKEY::url);
        r.add("app.events.consumer.enabled", () -> "false");
        r.add("app.realtime.heartbeat-ms", () -> "400");
        r.add("app.realtime.max-streams-per-user", () -> "3");
    }

    @AfterAll
    static void stopValkey() {
        VALKEY.close();
    }

    @Autowired ConsumerGroups groups;
    @Autowired StringRedisTemplate valkey;
    @Autowired RealtimeHub hub;

    final HttpClient client = HttpClient.newHttpClient();
    final List<Stream> open = new CopyOnWriteArrayList<>();
    SeededTenant tenant;
    SeededStaff fullStaff;
    SeededStaff queueOnly;

    @BeforeEach
    void setUp() {
        drain();
        tenant = seedTenant();
        fullStaff = staff(tenant, seedFullAccessRole(tenant.id()));
        queueOnly = staff(tenant, seedRole(tenant.id(), "Queue only", false, fullGrant("queue")));
    }

    @AfterEach
    void closeStreams() {
        open.forEach(Stream::close);
    }

    // ---- 1. snapshot, then a delta in well under 2 s; deltas carry ids, never names ----

    @Test
    void snapshotOnConnectThenDeltaWithinTwoSecondsAndNoNames() throws Exception {
        Stream desk = connect(token(fullStaff), "queue", null);
        Frame snap = desk.next(3000);
        assertThat(snap.event).isEqualTo("snapshot");
        assertThat(snap.json().path("type").asString()).isEqualTo("snapshot");
        assertThat(snap.json().path("topics").toString()).isEqualTo("[\"queue\"]");
        Thread.sleep(300); // the pod's channel subscription is asynchronous

        UUID entry = UUID.randomUUID();
        long t0 = System.nanoTime();
        xadd("ev:in:visit", queueChanged(nextId(), entry, 7, "waiting"));
        drain();
        Frame delta = desk.nextDelta(2000);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertThat(ms).as("publish to desk B's screen").isLessThan(2000);
        JsonNode d = delta.json();
        assertThat(d.path("type").asString()).isEqualTo("visit.queue_entry.changed.v1");
        assertThat(d.path("id").asString()).isEqualTo(entry.toString());
        assertThat(d.path("v").asLong()).isEqualTo(7);
        assertThat(d.path("fields").path("status").asString()).isEqualTo("waiting");
        assertThat(d.toString()).doesNotContain("tenantId").doesNotContain("name");
        assertThat(delta.id).matches("\\d+-\\d+");
    }

    @Test
    void theSameCloudEventDeliveredTwiceIsOneDelta() throws Exception {
        Stream s = connectReady(token(fullStaff), "queue");
        Map<String, Object> env = queueChanged(nextId(), UUID.randomUUID(), 3, "waiting");
        xadd("ev:in:visit", env);
        xadd("ev:in:visit", env);
        drain();
        s.nextDelta(2000);
        assertThat(s.pollDelta(600)).as("redelivery produced no second delta").isNull();
    }

    // ---- COM-006: a message status change reaches an open patient file ----

    @Test
    void messageStatusChangeReachesTheMessagesTopicWithinTwoSecondsForPatientsViewOnly() throws Exception {
        assertThat(get(token(queueOnly), "messages").statusCode()).isEqualTo(403); // no patients:view
        Stream s = connectReady(token(fullStaff), "messages");
        UUID msg = UUID.randomUUID();
        long t0 = System.nanoTime();
        xadd("ev:in:comms", envelope(nextId(), "comms.message.status_changed.v1", msg, Map.of("tenantId", tenant.id().toString(),
                "messageId", msg.toString(), "channel", "whatsapp", "status", "read", "at", Instant.now().toString(), "version", 1)));
        drain();
        JsonNode d = s.nextDelta(2000).json();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(2000);
        assertThat(d.path("type").asString()).isEqualTo("comms.message.status_changed.v1");
        assertThat(d.path("id").asString()).isEqualTo(msg.toString());
        assertThat(d.path("topic").asString()).isEqualTo("messages");
        assertThat(d.path("fields").path("status").asString()).isEqualTo("read");
    }

    // ---- 2. resume ----

    @Test
    void reconnectWithLastEventIdReplaysMissedDeltasWithoutASnapshotAndOldIdsGetOne() throws Exception {
        String token = token(fullStaff);
        Stream first = connectReady(token, "queue");
        xadd("ev:in:visit", queueChanged(nextId(), UUID.randomUUID(), 1, "waiting"));
        drain();
        Frame seen = first.nextDelta(2000);
        first.close();

        UUID missed1 = UUID.randomUUID();
        UUID missed2 = UUID.randomUUID();
        xadd("ev:in:visit", queueChanged(nextId(), missed1, 2, "called"));
        xadd("ev:in:visit", queueChanged(nextId(), missed2, 3, "in_consultation"));
        drain();

        Stream again = connect(token, "queue", seen.id);
        Frame r1 = again.nextDelta(3000);
        Frame r2 = again.nextDelta(3000);
        assertThat(again.events).as("no new snapshot on resume").doesNotContain("snapshot");
        assertThat(r1.json().path("id").asString()).isEqualTo(missed1.toString());
        assertThat(r2.json().path("id").asString()).isEqualTo(missed2.toString());
        again.close();

        long sixMinutesAgo = System.currentTimeMillis() - 6 * 60_000;
        Stream stale = connect(token, "queue", sixMinutesAgo + "-0");
        assertThat(stale.next(3000).event).as("after 5 minutes: a fresh snapshot").isEqualTo("snapshot");
        Stream garbage = connect(token, "queue", "not-an-id");
        assertThat(garbage.next(3000).event).isEqualTo("snapshot");
    }

    // ---- 3. heartbeat ----

    @Test
    void idleConnectionGetsHeartbeats() throws Exception {
        Stream s = connectReady(token(fullStaff), "queue");
        Frame h1 = s.next(3000);
        Frame h2 = s.next(3000);
        assertThat(h1.event).isEqualTo("heartbeat");
        assertThat(h2.event).isEqualTo("heartbeat");
        assertThat(h1.id).as("a heartbeat never moves Last-Event-ID").isNull();
    }

    @Test
    void heartbeatDefaultIs25Seconds() throws Exception {
        Scheduled s = RealtimeHub.class.getMethod("heartbeat").getAnnotation(Scheduled.class);
        assertThat(s.fixedDelayString()).contains("25000");
    }

    // ---- 4. permissions ----

    @Test
    void withoutNursingViewTheNursingTopicIs403AndNoNursingDeltaIsEverSent() throws Exception {
        HttpResponse<String> r = get(token(queueOnly), "queue,nursing");
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).contains("TOPIC_FORBIDDEN");
        assertThat(get(token(queueOnly), "requests").statusCode()).isEqualTo(403);
        assertThat(get(token(queueOnly), "bogus").statusCode()).isEqualTo(400);

        Stream s = connectReady(token(queueOnly), null); // no topics = every permitted one
        assertThat(s.snapshotTopics()).isEqualTo("[\"queue\",\"doctor-status\",\"exceptions\"]");
        xadd("ev:in:visit", queueChanged(nextId(), UUID.randomUUID(), 1, "waiting")); // goes to queue AND nursing
        drain();
        List<Frame> got = new ArrayList<>();
        got.add(s.nextDelta(2000));
        Frame extra;
        while ((extra = s.pollDelta(600)) != null) {
            got.add(extra);
        }
        assertThat(got).extracting(f -> f.json().path("topic").asString()).containsOnly("queue");
        assertThat(got).hasSize(1);
        assertThat(connectReady(token(fullStaff), "nursing")).isNotNull(); // the permitted role may
    }

    @Test
    void aStaffChangeEndsTheirOpenStreamsSoTheyReconnectAndAreChecked() throws Exception {
        Stream s = connectReady(token(fullStaff), "queue,nursing");
        Stream other = connectReady(token(queueOnly), "queue");
        xadd("ev:in:iam", envelope(nextId(), "iam.staff.changed.v1", fullStaff.id(),
                Map.of("tenantId", tenant.id().toString(), "staffId", fullStaff.id().toString(), "status", "active", "version", 1)));
        drain();
        assertThat(s.waitClosed(3000)).as("revoked staff stream ends").isTrue();
        assertThat(other.waitClosed(700)).as("someone else's stream stays").isFalse();
    }

    // ---- 5. caps ----

    @Test
    void fourthStreamOfOneUserIs429TooManyStreams() throws Exception {
        String token = token(fullStaff);
        Stream a = connectReady(token, "queue");
        connectReady(token, "queue");
        connectReady(token, "queue");
        HttpResponse<String> r = get(token, "queue");
        assertThat(r.statusCode()).isEqualTo(429);
        assertThat(r.body()).contains("TOO_MANY_STREAMS");
        a.close(); // the server notices on its next heartbeat write
        long end = System.currentTimeMillis() + 5000;
        int status;
        HttpResponse<InputStream> again;
        do {
            again = client.send(HttpRequest.newBuilder(URI.create(url("/v1/events?topics=queue")))
                    .header("Authorization", "Bearer " + token).header("Accept", "text/event-stream").build(), HttpResponse.BodyHandlers.ofInputStream());
            status = again.statusCode();
            if (status == 429) {
                again.body().close();
                Thread.sleep(200);
            }
        } while (status == 429 && System.currentTimeMillis() < end);
        assertThat(status).as("a slot freed once the dead stream was noticed").isEqualTo(200);
        again.body().close();
    }

    // ---- 6. isolation ----

    @Test
    void aDeltaForTenantAIsNeverWrittenToTenantBOverHttp() throws Exception {
        SeededTenant other = seedTenant();
        SeededStaff b = staff(other, seedFullAccessRole(other.id()));
        Stream a = connectReady(token(fullStaff), "queue");
        Stream bs = connectReady(token(b), "queue");
        xadd("ev:in:visit", queueChanged(nextId(), UUID.randomUUID(), 1, "waiting"));
        drain();
        a.nextDelta(2000);
        assertThat(bs.pollDelta(800)).isNull();
    }

    @Test
    void hundredTenantsOnOneNodeEachSeeOnlyTheirOwnDeltasAndTheNodeIsFast() throws Exception {
        int n = 100;
        List<UUID> tenants = new ArrayList<>();
        Map<UUID, Rec> sinks = new LinkedHashMap<>();
        List<RealtimeConnection> conns = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID t = UUID.randomUUID();
            Rec r = new Rec();
            tenants.add(t);
            sinks.put(t, r);
            conns.add(hub.open(t, UUID.randomUUID(), null, EnumSet.of(RealtimeTopic.QUEUE, RealtimeTopic.REQUESTS), null, null, r));
        }
        Thread.sleep(500);
        List<Long> ms = new ArrayList<>();
        long start = System.nanoTime();
        for (UUID t : tenants) {
            hub.publish(t, RealtimeTopic.QUEUE, "{\"type\":\"x\",\"id\":\"" + t + "\",\"v\":1}");
        }
        for (UUID t : tenants) {
            Rec r = sinks.get(t);
            assertThat(r.awaitDeltas(1, 2000)).as("tenant %s got its delta", t).isTrue();
            ms.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
        for (UUID t : tenants) {
            List<String> deltas = sinks.get(t).deltas();
            assertThat(deltas).hasSize(1);
            assertThat(deltas.get(0)).contains(t.toString()); // its own marker, nobody else's
        }
        ms.sort(Long::compare);
        long p95 = ms.get((int) Math.ceil(n * 0.95) - 1);
        System.out.println("ACC-021 local fan-out, 100 tenants: p95 publish-to-sink " + p95 + " ms");
        assertThat(p95).isLessThan(2000);
        conns.forEach(hub::unregister);
        assertThat(sinks.values()).allMatch(r -> r.completed);
    }

    // ---- 7. requests topic, token expiry ----

    @Test
    void appointmentEventsReachTheRequestsTopicAndAnExpiredTokenEndsTheStream() throws Exception {
        UUID t = tenant.id();
        Rec rec = new Rec();
        hub.open(t, UUID.randomUUID(), null, EnumSet.of(RealtimeTopic.REQUESTS), Instant.now().plusMillis(1200), null, rec);
        Thread.sleep(300);
        UUID appt = UUID.randomUUID();
        xadd("ev:in:scheduling", envelope(nextId(), "scheduling.appointment.booked.v1", appt, Map.of("tenantId", t.toString(),
                "appointmentId", appt.toString(), "doctorId", UUID.randomUUID().toString(), "startTime", "2026-10-21T09:00:00Z",
                "status", "booked", "version", 4)));
        drain();
        assertThat(rec.awaitDeltas(1, 2000)).isTrue();
        assertThat(rec.deltas().get(0)).contains("\"topic\":\"requests\"").contains(appt.toString());
        Thread.sleep(1000);
        hub.heartbeat();
        assertThat(rec.completed).as("expired token ends the stream").isTrue();
    }

    // ---- helpers ----

    static final class Rec implements RealtimeConnection.Sink {
        final List<String> frames = new CopyOnWriteArrayList<>();
        volatile boolean completed;

        public void send(String event, String id, String data) {
            frames.add(event + "|" + data);
        }

        public void complete() {
            completed = true;
        }

        List<String> deltas() {
            return frames.stream().filter(f -> f.startsWith("delta|")).toList();
        }

        boolean awaitDeltas(int count, long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            while (deltas().size() < count && System.currentTimeMillis() < end) {
                Thread.sleep(5);
            }
            return deltas().size() >= count;
        }
    }

    record Frame(String event, String id, String data, tools.jackson.databind.ObjectMapper m) {
        JsonNode json() {
            return m.readTree(data);
        }
    }

    final class Stream {
        final BlockingQueue<Frame> frames = new LinkedBlockingQueue<>();
        final InputStream in;
        volatile boolean eof;
        final List<String> events = new CopyOnWriteArrayList<>();
        Frame snapshot;

        Stream(InputStream in) {
            this.in = in;
            Thread.ofVirtual().start(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String event = null, id = null, data = null, line;
                    while ((line = r.readLine()) != null) {
                        if (line.isEmpty()) {
                            if (data != null) {
                                events.add(event);
                                frames.add(new Frame(event, id, data, objectMapper));
                            }
                            event = id = data = null;
                        } else if (line.startsWith("event:")) {
                            event = line.substring(6).trim();
                        } else if (line.startsWith("id:")) {
                            id = line.substring(3).trim();
                        } else if (line.startsWith("data:")) {
                            data = line.substring(5).trim();
                        }
                    }
                } catch (IOException ignored) {
                    // closed by the test
                }
                eof = true;
            });
        }

        Frame next(long ms) throws InterruptedException {
            Frame f = frames.poll(ms, TimeUnit.MILLISECONDS);
            assertThat(f).as("an SSE frame within %d ms", ms).isNotNull();
            return f;
        }

        Frame pollDelta(long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < end) {
                Frame f = frames.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (f != null && "delta".equals(f.event)) {
                    return f;
                }
            }
            return null;
        }

        Frame nextDelta(long ms) throws InterruptedException {
            Frame f = pollDelta(ms);
            assertThat(f).as("a delta within %d ms", ms).isNotNull();
            return f;
        }

        String snapshotTopics() {
            return snapshot.json().path("topics").toString();
        }

        boolean waitClosed(long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            while (!eof && System.currentTimeMillis() < end) {
                Thread.sleep(20);
            }
            return eof;
        }

        void close() {
            try {
                in.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    /** Opens the stream, consumes the snapshot and waits for the pod's channel subscription. */
    Stream connectReady(String token, String topics) throws Exception {
        Stream s = connect(token, topics, null);
        s.snapshot = s.next(3000);
        assertThat(s.snapshot.event).isEqualTo("snapshot");
        Thread.sleep(300);
        return s;
    }

    Stream connect(String token, String topics, String lastEventId) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url("/v1/events" + (topics == null ? "" : "?topics=" + topics))))
                .header("Authorization", "Bearer " + token).header("Accept", "text/event-stream");
        if (lastEventId != null) {
            b.header("Last-Event-ID", lastEventId);
        }
        HttpResponse<InputStream> r = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        Stream s = new Stream(r.body());
        open.add(s);
        return s;
    }

    HttpResponse<String> get(String token, String topics) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url("/v1/events?topics=" + topics)))
                .header("Authorization", "Bearer " + token).header("Accept", "text/event-stream").build(), HttpResponse.BodyHandlers.ofString());
    }

    SeededStaff staff(SeededTenant t, UUID role) {
        return seedStaff(t, role, "rt-" + UUID.randomUUID() + "@a.com", String.format("+9198%08d", IDS.incrementAndGet() % 100_000_000L), false);
    }

    String token(SeededStaff s) {
        return loginAndGetAccessToken(s);
    }

    void drain() {
        for (int i = 0; i < 20 && !groups.pollOnce().isEmpty(); i++) {
            // until a pass finds nothing
        }
    }

    void xadd(String stream, Map<String, Object> envelope) {
        valkey.opsForStream().add(StreamRecords.newRecord().in(stream)
                .ofMap(Map.of(ConsumerGroups.FIELD, objectMapper.writeValueAsString(envelope))));
    }

    static String nextId() {
        return "rt-" + IDS.incrementAndGet();
    }

    Map<String, Object> queueChanged(String id, UUID entry, long version, String status) {
        return envelope(id, "visit.queue_entry.changed.v1", entry, Map.of("tenantId", tenant.id().toString(), "entryId", entry.toString(),
                "doctorId", UUID.randomUUID().toString(), "queueDate", "2026-10-21", "status", status, "version", version));
    }

    Map<String, Object> envelope(String id, String type, UUID subject, Map<String, Object> data) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("specversion", "1.0");
        e.put("id", id);
        e.put("source", "core/test");
        e.put("type", type);
        e.put("time", Instant.now().toString());
        e.put("subject", subject.toString());
        e.put("tenantid", tenant.id().toString());
        e.put("region", "in");
        e.put("dataschema", "urn:nabd:event-schema:" + type);
        e.put("datacontenttype", "application/json");
        e.put("data", data);
        return e;
    }
}
