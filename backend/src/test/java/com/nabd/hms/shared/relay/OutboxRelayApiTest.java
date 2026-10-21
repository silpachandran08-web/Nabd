package com.nabd.hms.shared.relay;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.shared.ops.OutboxMetrics;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ValkeyTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPS-016 acceptance: leader election between two relays, failover, NOTIFY wake and fallback poll, envelope and MAXLEN,
 * Valkey outage, 5,000-event burst, and the dispatcher/relay split. Relays are built here with the production
 * constructor and their own meter registry, as two pods would be; the context itself runs none (app.relay.enabled unset).
 */
class OutboxRelayApiTest extends ApiTestBase {

    static final ValkeyTestSupport VALKEY = ValkeyTestSupport.start();
    static final String CREATED = DomainEventType.QUEUE_ENTRY_CREATED.type();

    @DynamicPropertySource
    static void valkey(DynamicPropertyRegistry r) {
        r.add("app.valkey.url", VALKEY::url);
    }

    @AfterAll
    static void stopValkey() {
        VALKEY.close();
    }

    @Autowired
    StringRedisTemplate valkey;
    @Autowired
    Environment env;
    @Autowired
    EventPublisher publisher;
    @Autowired
    OutboxDispatcher dispatcher;
    @Autowired
    OutboxMetrics outboxMetrics;
    @Autowired
    MeterRegistry meters;
    @Autowired
    RelayProperties shipped;

    private final List<OutboxRelay> started = new ArrayList<>();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void emptyStreams() {
        awaitTrue(30_000, () -> {
            try {
                valkey.getConnectionFactory().getConnection().serverCommands().flushAll();
                return true;
            } catch (RuntimeException e) {
                return false; // reconnecting after an outage test
            }
        });
    }

    @AfterEach
    void stopRelays() {
        started.forEach(OutboxRelay::stop);
    }

    private static RelayProperties props(int leaseSeconds, int pollMs, long maxlen) {
        return new RelayProperties(true, 500, leaseSeconds, pollMs, 500, 200, maxlen, null, null, null);
    }

    private OutboxRelay relay(SimpleMeterRegistry m, RelayProperties p) {
        OutboxRelay r = new OutboxRelay(jdbc, valkey, p, OutboxRelay.direct(env.getProperty("spring.datasource.url"),
                env.getProperty("spring.datasource.username"), env.getProperty("spring.datasource.password")), m);
        started.add(r);
        r.start();
        return r;
    }

    private static double leaderGauge(SimpleMeterRegistry m) {
        return m.get("relay.leader").gauge().value();
    }

    private static void awaitTrue(long ms, BooleanSupplier cond) {
        long deadline = System.currentTimeMillis() + ms;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within " + ms + " ms");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    /** Publishes through the real producer API; returns when the transaction has committed. */
    private UUID publishEvent(SeededTenant t) {
        return publishEvent(t.id());
    }

    private UUID publishEvent(UUID tenant) {
        UUID entry = UUID.randomUUID();
        inTenantTx(tenant, () -> publisher.publish(tenant, DomainEventType.QUEUE_ENTRY_CREATED, entry,
                Map.of("entryId", entry, "doctorId", UUID.randomUUID(), "queueDate", "2026-10-20", "status", "waiting")));
        return entry;
    }

    private long publishOne(SeededTenant t) {
        return publishOne(t.id());
    }

    private long publishOne(UUID tenant) {
        UUID entry = publishEvent(tenant);
        return inTenantTx(tenant, () -> jdbc.queryForObject(
                "SELECT id FROM outbox_events WHERE tenant_id = ? AND aggregate_id = ?", Long.class, tenant, entry));
    }

    /** n domain rows in one statement (one NOTIFY at commit). */
    private void publishMany(SeededTenant t, int n) {
        inTenantTx(t.id(), () -> jdbc.update("""
                INSERT INTO outbox_events (tenant_id, event_type, payload, ce_type, ce_source, aggregate_id)
                SELECT ?, ?, jsonb_build_object('tenantId', ?::text, 'entryId', gen_random_uuid(), 'status', 'waiting'), ?, 'core/visit',
                       gen_random_uuid()
                FROM generate_series(1, ?)""", t.id(), CREATED, t.id().toString(), CREATED, n));
    }

    private long countStatus(SeededTenant t, String status) {
        return new JdbcTemplate(superuserDataSource()).queryForObject(
                "SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND status = ?", Long.class, t.id(), status);
    }

    private Set<Long> rowIds(SeededTenant t) {
        return new HashSet<>(new JdbcTemplate(superuserDataSource()).queryForList(
                "SELECT id FROM outbox_events WHERE tenant_id = ?", Long.class, t.id()));
    }

    private List<JsonNode> entries(String stream) {
        var records = valkey.opsForStream().range(stream, Range.unbounded());
        List<JsonNode> out = new ArrayList<>();
        for (var r : records == null ? List.<org.springframework.data.redis.connection.stream.MapRecord<String, Object, Object>>of() : records) {
            try {
                out.add(json.readTree((String) r.getValue().get("ce")));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
        return out;
    }

    private Set<Long> streamIds(String stream) {
        Set<Long> ids = new HashSet<>();
        entries(stream).forEach(e -> ids.add(Long.parseLong(e.get("id").asString())));
        return ids;
    }

    private long xlen(String stream) {
        Long n = valkey.opsForStream().size(stream);
        return n == null ? 0 : n;
    }

    private static long heldRelayLocks() {
        return new JdbcTemplate(superuserDataSource()).queryForObject("""
                SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND granted
                  AND ((classid::bigint << 32) | objid::bigint) = ?""", Long.class, OutboxRelay.LOCK_KEY);
    }

    // AC1
    @Test
    void ofTwoRelaysExactlyOneHoldsTheLockAndTheOtherStandsBy() throws Exception {
        SimpleMeterRegistry ma = new SimpleMeterRegistry(), mb = new SimpleMeterRegistry();
        relay(ma, props(10, 2000, 1_000_000));
        relay(mb, props(10, 2000, 1_000_000));
        awaitTrue(5000, () -> leaderGauge(ma) + leaderGauge(mb) == 1);
        for (int i = 0; i < 6; i++) { // stays that way across several standby lock retries (500 ms)
            Thread.sleep(500);
            assertThat(leaderGauge(ma) + leaderGauge(mb)).isEqualTo(1.0);
            assertThat(Set.of(leaderGauge(ma), leaderGauge(mb))).containsExactlyInAnyOrder(0.0, 1.0);
        }
        assertThat(heldRelayLocks()).isEqualTo(1);
    }

    // AC2
    @Test
    void whenTheLeaderIsKilledTheStandbyTakesOverWithin10sAndNoEventIsLost() throws Exception {
        SeededTenant t = seedTenant();
        SimpleMeterRegistry ma = new SimpleMeterRegistry(), mb = new SimpleMeterRegistry();
        OutboxRelay a = relay(ma, props(2, 2000, 1_000_000));
        awaitTrue(5000, a::isLeader);
        OutboxRelay b = relay(mb, props(2, 2000, 1_000_000));
        Thread.sleep(700);
        assertThat(b.isLeader()).isFalse();

        publishMany(t, 50);
        awaitTrue(5000, () -> countStatus(t, "done") == 50);

        a.stop(); // pod killed: its lock connection closes
        long killedAt = System.currentTimeMillis();
        publishMany(t, 50); // committed while there is no leader
        // and the dead leader had claimed some of them in a batch it never finished (lease 2 s)
        new JdbcTemplate(superuserDataSource()).query("SELECT * FROM claim_relay_events(10, 2)", rs -> {});

        awaitTrue(10_000, b::isLeader);
        assertThat(System.currentTimeMillis() - killedAt).isLessThan(10_000);
        assertThat(leaderGauge(mb)).isEqualTo(1.0);
        assertThat(leaderGauge(ma)).isEqualTo(0.0);

        awaitTrue(10_000, () -> countStatus(t, "done") == 100);
        assertThat(streamIds("ev:in:visit")).containsExactlyInAnyOrderElementsOf(rowIds(t)).hasSize(100);
    }

    // AC3, NOTIFY path: the fallback poll is a minute here, so only the notification can wake the relay in time
    @Test
    void anIdleRelayXaddsACommittedEventWithin500msP95() {
        SeededTenant t = seedTenant();
        OutboxRelay r = relay(new SimpleMeterRegistry(), props(10, 60_000, 1_000_000));
        awaitTrue(5000, r::isLeader);
        publishOne(t); // warm up (first claim, first XADD connection)
        awaitTrue(5000, () -> xlen("ev:in:visit") == 1);

        List<Long> latencies = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long before = xlen("ev:in:visit");
            publishEvent(t);
            long committed = System.nanoTime();
            awaitTrue(5000, () -> xlen("ev:in:visit") == before + 1);
            latencies.add((System.nanoTime() - committed) / 1_000_000);
        }
        Collections.sort(latencies);
        long p95 = latencies.get((int) Math.ceil(latencies.size() * 0.95) - 1);
        System.out.println("OPS-016 commit-to-XADD ms (sorted): " + latencies + " p95=" + p95);
        assertThat(p95).isLessThan(500);
    }

    // AC3, fallback poll: rows inserted with triggers off (session_replication_role = replica) send no NOTIFY
    @Test
    void whenTheNotificationIsLostTheFallbackPollRelaysWithin2500ms() throws Exception {
        SeededTenant t = seedTenant();
        OutboxRelay r = relay(new SimpleMeterRegistry(), props(10, 2000, 1_000_000));
        awaitTrue(5000, r::isLeader);
        for (int i = 0; i < 3; i++) {
            long before = xlen("ev:in:visit");
            try (Connection su = superuserConnection(); Statement st = su.createStatement()) {
                st.execute("SET session_replication_role = replica");
                st.executeUpdate("INSERT INTO outbox_events (tenant_id, event_type, payload, ce_type, ce_source, aggregate_id) VALUES ('"
                        + t.id() + "', '" + CREATED + "', '{\"status\":\"waiting\"}', '" + CREATED + "', 'core/visit', gen_random_uuid())");
            }
            long committed = System.nanoTime();
            awaitTrue(2500, () -> xlen("ev:in:visit") == before + 1);
            System.out.println("OPS-016 lost-NOTIFY commit-to-XADD ms: " + (System.nanoTime() - committed) / 1_000_000);
            Thread.sleep(300 * i); // land at different points of the poll interval
        }
    }

    // AC4
    @Test
    void streamEntriesCarryTheCloudEventsEnvelopeAndMaxlenTrimsApproximately() throws Exception {
        SeededTenant in = seedTenant();
        UUID ksa = UUID.randomUUID(); // region is immutable after provisioning, so this tenant is created in KSA
        String slug = "k" + ksa.toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO tenants (id, slug, name, region, status) VALUES (?,?,?,'KSA','active')", ksa, slug, "KSA " + slug);
        OutboxRelay r = relay(new SimpleMeterRegistry(), props(10, 2000, 100));
        awaitTrue(5000, r::isLeader);

        long id = publishOne(in);
        long ksaId = publishOne(ksa);
        awaitTrue(5000, () -> xlen("ev:in:visit") == 1 && xlen("ev:ksa:visit") == 1);

        JsonNode ce = entries("ev:in:visit").get(0);
        assertThat(ce.get("specversion").asString()).isEqualTo("1.0");
        assertThat(ce.get("id").asString()).isEqualTo(String.valueOf(id));
        assertThat(ce.get("type").asString()).isEqualTo(CREATED);
        assertThat(ce.get("source").asString()).isEqualTo("core/visit");
        assertThat(ce.get("tenantid").asString()).isEqualTo(in.id().toString());
        assertThat(ce.get("region").asString()).isEqualTo("in");
        assertThat(ce.get("dataschema").asString()).isEqualTo("urn:nabd:event-schema:" + CREATED);
        assertThat(ce.get("datacontenttype").asString()).isEqualTo("application/json");
        assertThat(ce.hasNonNull("time")).isTrue();
        assertThat(UUID.fromString(ce.get("subject").asString())).isNotNull();
        assertThat(ce.get("data").get("version").asLong()).isEqualTo(id);
        assertThat(ce.get("data").get("status").asString()).isEqualTo("waiting");
        String expected = inTenantTx(in.id(), () -> jdbc.queryForObject("SELECT envelope::text FROM outbox_cloudevents WHERE id = ?", String.class, id));
        assertThat(ce).isEqualTo(json.readTree(expected)); // exactly the one rendering (V1550)
        assertThat(streamIds("ev:ksa:visit")).containsExactly(ksaId);

        publishMany(in, 1000); // MAXLEN ~ 100: whole nodes (100 entries) are trimmed, never below 100
        awaitTrue(10_000, () -> countStatus(in, "done") == 1001);
        assertThat(xlen("ev:in:visit")).isBetween(100L, 300L);

        assertThat(shipped.streamMaxlen()).isEqualTo(1_000_000L); // the documented 7-day number
        assertThat(Files.readString(Path.of("../docs/architecture/events.md"))).contains("MAXLEN ~ 1,000,000");
    }

    // AC5
    @Test
    @SuppressWarnings("unchecked")
    void whenValkeyIsDownEventsStayPendingErrorsCountAndTheAgeAlertFires() throws Exception {
        SeededTenant t = seedTenant();
        SimpleMeterRegistry m = new SimpleMeterRegistry();
        OutboxRelay r = relay(m, props(10, 2000, 1_000_000));
        awaitTrue(5000, r::isLeader);
        VALKEY.stop();
        try {
            publishMany(t, 5);
            awaitTrue(5000, () -> m.counter("relay.errors").count() >= 2); // first try and at least one retry
            assertThat(countStatus(t, "done")).isZero();
            assertThat(countStatus(t, "pending") + countStatus(t, "processing")).isEqualTo(5);

            new JdbcTemplate(superuserDataSource()).update(
                    "UPDATE outbox_events SET created_at = now() - interval '45 seconds' WHERE tenant_id = ?", t.id());
            outboxMetrics.refresh();
            assertThat(meters.get("outbox.oldest.pending.age.seconds").tag("region", "IN").gauge().value()).isGreaterThan(30);
        } finally {
            VALKEY.restart();
        }
        // Lettuce reconnects in the background; the same events then go out, and only then are they done
        awaitTrue(30_000, () -> countStatus(t, "done") == 5);
        assertThat(streamIds("ev:in:visit")).containsExactlyInAnyOrderElementsOf(rowIds(t));

        Map<String, Object> doc;
        try (InputStream in = Files.newInputStream(Path.of("../infra/observability/outbox-relay.rules.yml"))) {
            doc = new Yaml().load(in);
        }
        List<Map<String, Object>> rules = (List<Map<String, Object>>) ((List<Map<String, Object>>) doc.get("groups")).get(0).get("rules");
        Map<String, Object> age = rules.stream().filter(x -> "NabdOutboxOldestPendingOver30s".equals(x.get("alert"))).findFirst().orElseThrow();
        assertThat((String) age.get("expr")).contains("outbox_oldest_pending_age_seconds").contains("> 30");
        assertThat(age.get("for")).isEqualTo("0m");
        assertThat(rules.stream().map(x -> (String) x.get("expr")).toList())
                .anyMatch(e -> e.contains("relay_errors_total")).anyMatch(e -> e.contains("relay_leader"));
    }

    // tests: perf, 5,000-event burst drains < 10 s
    @Test
    void aBurstOf5000EventsDrainsWithin10s() {
        SeededTenant t = seedTenant();
        OutboxRelay r = relay(new SimpleMeterRegistry(), props(10, 2000, 1_000_000));
        awaitTrue(5000, r::isLeader);
        publishMany(t, 5000);
        long start = System.nanoTime();
        awaitTrue(10_000, () -> countStatus(t, "done") == 5000);
        System.out.println("OPS-016 5000-event burst drained in ms: " + (System.nanoTime() - start) / 1_000_000);
        assertThat(xlen("ev:in:visit")).isEqualTo(5000);
    }

    // coexistence: with Valkey configured the dispatcher never claims a domain row, whatever handlers exist
    @Test
    void theDispatcherLeavesDomainRowsToTheRelayAndStillFinishesLegacyRows() {
        SeededTenant t = seedTenant();
        long domain = publishOne(t);
        long legacy = inTenantTx(t.id(), () -> jdbc.queryForObject(
                "INSERT INTO outbox_events (tenant_id, event_type, payload) VALUES (?, 'test.echo', '{}'::jsonb) RETURNING id", Long.class, t.id()));
        dispatcher.poll();
        Map<Long, String> status = new java.util.HashMap<>();
        new JdbcTemplate(superuserDataSource()).query("SELECT id, status FROM outbox_events WHERE tenant_id = ?",
                rs -> { status.put(rs.getLong(1), rs.getString(2)); }, t.id());
        assertThat(status).containsEntry(domain, "pending").containsEntry(legacy, "done");
        assertThat(xlen("ev:in:visit")).isZero();
    }

    // fencing: a relay whose lease ran out cannot finish (or reset) the batch another relay reclaimed
    @Test
    void aStaleRelayFinishUpdatesNothing() throws Exception {
        SeededTenant t = seedTenant();
        long id = publishOne(t);
        JdbcTemplate app = jdbc;
        int stale = app.queryForObject("SELECT attempts FROM claim_relay_events(10, 1) WHERE id = ?", Integer.class, id);
        Thread.sleep(1100);
        int owner = app.queryForObject("SELECT attempts FROM claim_relay_events(10, 60) WHERE id = ?", Integer.class, id);
        assertThat(owner).isEqualTo(stale + 1);
        for (boolean done : new boolean[]{true, false}) {
            assertThat(app.queryForList("SELECT * FROM finish_relay_events(?::bigint[], ?::int[], ?, 'x')", Long.class,
                    "{" + id + "}", "{" + stale + "}", done)).isEmpty();
        }
        assertThat(countStatus(t, "processing")).isEqualTo(1);
        assertThat(app.queryForList("SELECT * FROM finish_relay_events(?::bigint[], ?::int[], true, null)", Long.class,
                "{" + id + "}", "{" + owner + "}")).containsExactly(id);
    }
}
