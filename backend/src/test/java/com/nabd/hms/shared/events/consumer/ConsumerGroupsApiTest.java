package com.nabd.hms.shared.events.consumer;

import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ValkeyTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OPS-017 against a real Redis 6.2 (Valkey stand-in). OPS-016's relay is not here, so the tests XADD CloudEvents envelopes
 * (events.md section 2, field "ce") themselves. Background polls are off; each test drives pollOnce() so nothing races.
 * Replicas are extra ConsumerGroups instances with their own consumer names over the same beans.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class ConsumerGroupsApiTest extends ApiTestBase {

    static final ValkeyTestSupport VALKEY = ValkeyTestSupport.start();
    static final String PROBE = "probe.item.changed.v1";
    static final AtomicLong IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L * 10);

    @DynamicPropertySource
    static void valkey(DynamicPropertyRegistry r) {
        r.add("app.valkey.url", VALKEY::url);
        r.add("app.events.consumer.enabled", () -> "false");
        r.add("app.events.consumer.name", () -> "replica-main");
        r.add("app.events.consumer.retry-base-ms", () -> "0"); // retries due at once; the backoff curve is checked below
    }

    @AfterAll
    static void stopValkey() {
        VALKEY.close();
    }

    /** A consumer that counts calls and fails on demand. */
    static class Probe implements EventConsumer {
        final String group;
        final Idempotency idem;
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean failing;

        Probe(String group, Idempotency idem) {
            this.group = group;
            this.idem = idem;
        }

        public String group() { return group; }
        public Set<String> types() { return Set.of(PROBE); }
        public Idempotency idempotency() { return idem; }
        public void handle(Event e) {
            calls.incrementAndGet();
            if (failing) {
                throw new IllegalStateException("probe failure with text that must never reach the DLQ");
            }
        }
    }

    @TestConfiguration
    static class Probes {
        @Bean
        Probe probeLedger() { return new Probe("probe", EventConsumer.Idempotency.DB_LEDGER); }

        @Bean
        Probe probeCache() { return new Probe("probe-cache", EventConsumer.Idempotency.VALKEY_SET); }
    }

    @Autowired ConsumerGroups groups;
    @Autowired StringRedisTemplate valkey;
    @Autowired ObjectProvider<StringRedisTemplate> valkeyProvider;
    @Autowired ObjectProvider<EventConsumer> consumers;
    @Autowired AppliedEventsRepository ledger;
    @Autowired TenantScope tenantScope;
    @Autowired ObjectMapper objectMapper;
    @Autowired ConsumerProperties props;
    @Autowired MeterRegistry meters;
    @Autowired Clock clock;
    @Autowired DlqReplayCommand cli;
    @Autowired List<Probe> probes;
    @LocalManagementPort int mgmtPort;

    Probe probe;
    Probe probeCache;
    SeededTenant tenant;
    UUID staffId;

    @BeforeEach
    void setUp() {
        probe = probes.stream().filter(p -> p.group.equals("probe")).findFirst().orElseThrow();
        probeCache = probes.stream().filter(p -> p.group.equals("probe-cache")).findFirst().orElseThrow();
        probe.failing = false;
        drain(groups);
        probe.calls.set(0);
        probeCache.calls.set(0);
        tenant = seedTenant();
        staffId = seedStaff(tenant, seedFullAccessRole(tenant.id()), "ops17-" + UUID.randomUUID() + "@a.com",
                String.format("+9198%08d", IDS.incrementAndGet() % 100_000_000L), false).id();
    }

    // ---- criterion 1: duplicate delivery to the projector is a no-op (applied-events ledger) ----

    @Test
    void theSameEventDeliveredTwiceToTheProjectorIsANoOpTheSecondTime() {
        UUID patient = seedPatientWithAllergy("test-substance-a", "moderate");
        String eventId = nextId();
        Map<String, Object> env = allergyEvent(eventId, patient, 100);

        xadd("ev:in:clinical", env);
        assertThat(drain(groups)).contains(ConsumerGroups.Outcome.APPLIED);
        assertThat(topAllergy(patient)).isEqualTo("test-substance-a");

        addAllergy(patient, "test-substance-b", "severe"); // a re-projection would now show b
        xadd("ev:in:clinical", env);                      // redelivery: same CloudEvents id, new stream entry
        assertThat(drain(groups)).containsExactly(ConsumerGroups.Outcome.DUPLICATE);
        assertThat(topAllergy(patient)).as("second delivery changed nothing").isEqualTo("test-substance-a");
        assertThat(ledgerRows(tenant.id(), "projector", eventId)).isEqualTo(1);
        assertThat(valkey.opsForStream().pending("ev:in:clinical", "projector").getTotalPendingMessages()).isZero();
    }

    // ---- criterion 4: version gate ----

    @Test
    void aLowerVersionAfterAHigherOneIsIgnoredByTheStateCarryingProjector() {
        UUID patient = seedPatientWithAllergy("test-substance-a", "moderate");
        xadd("ev:in:clinical", allergyEvent(nextId(), patient, 5));
        assertThat(drain(groups)).containsExactly(ConsumerGroups.Outcome.APPLIED);

        addAllergy(patient, "test-substance-b", "severe");
        xadd("ev:in:clinical", allergyEvent(nextId(), patient, 4));
        assertThat(drain(groups)).containsExactly(ConsumerGroups.Outcome.STALE);
        assertThat(topAllergy(patient)).as("v=4 after v=5 was not applied").isEqualTo("test-substance-a");

        xadd("ev:in:clinical", allergyEvent(nextId(), patient, 6));
        assertThat(drain(groups)).containsExactly(ConsumerGroups.Outcome.APPLIED);
        assertThat(topAllergy(patient)).isEqualTo("test-substance-b");
    }

    @Test
    void theLedgerIsTenantIsolatedUnderForcedRls() {
        xadd("ev:in:probe", probeEvent(nextId(), tenant.id(), UUID.randomUUID(), 1));
        drain(groups);
        SeededTenant other = seedTenant();
        assertThat(inTenantTx(tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM projector_applied_events", Long.class))).isEqualTo(1);
        assertThat(inTenantTx(other.id(), () -> jdbc.queryForObject("SELECT count(*) FROM projector_applied_events WHERE tenant_id = ?",
                Long.class, tenant.id()))).isZero();
        assertThat(new JdbcTemplate(superuserDataSource()).queryForObject(
                "SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE relname = 'projector_applied_events'", Boolean.class)).isTrue();
    }

    @Test
    void aStatelessConsumerDedupesWithAValkeyKeyThatHasATtl() {
        String id = nextId();
        Map<String, Object> env = probeEvent(id, tenant.id(), UUID.randomUUID(), 1);
        xadd("ev:in:probe", env);
        xadd("ev:in:probe", env);
        drain(groups);
        assertThat(probeCache.calls.get()).isEqualTo(1);
        assertThat(probe.calls.get()).isEqualTo(1);
        assertThat(valkey.getExpire("consumer:applied:{probe-cache}:" + id)).isPositive();
    }

    // ---- criterion 2: crash mid-batch, recovered by restart or by XAUTOCLAIM after 60 s ----

    @Test
    void aCrashMidBatchIsRecoveredByARestartOrByAnotherInstanceAfterTheClaimIdleTime() throws Exception {
        EventConsumer probeConsumer = probe;
        for (int i = 0; i < 3; i++) {
            xadd("ev:in:probe", probeEvent(nextId(), tenant.id(), UUID.randomUUID(), 1));
        }
        ConsumerGroups a = replica("replica-a", props);
        List<MapRecord<String, Object, Object>> batch = readAs("replica-a", 3);
        assertThat(batch).hasSize(3);
        assertThat(a.deliver(valkey, probeConsumer, batch.get(0))).isEqualTo(ConsumerGroups.Outcome.APPLIED);
        // ... and replica-a dies here: entries 2 and 3 are delivered to it but never acknowledged
        assertThat(pendingFor("replica-a")).isEqualTo(2);

        ConsumerGroups b = replica("replica-b", props);
        assertThat(props.claimIdleMs()).isEqualTo(60_000);
        b.pollOnce();
        assertThat(pendingFor("replica-a")).as("a live peer does not steal entries younger than 60 s").isEqualTo(2);
        assertThat(probe.calls.get()).isEqualTo(1);

        replica("replica-a", props).pollOnce(); // restart with the same consumer name
        assertThat(probe.calls.get()).isEqualTo(3);
        assertThat(pendingFor("replica-a")).isZero();

        // the crashed consumer never comes back: a peer XAUTOCLAIMs after the idle time (1 s here instead of 60 s)
        for (int i = 0; i < 2; i++) {
            xadd("ev:in:probe", probeEvent(nextId(), tenant.id(), UUID.randomUUID(), 1));
        }
        assertThat(readAs("replica-c", 2)).hasSize(2);
        ConsumerGroups d = replica("replica-d", withClaimIdle(1000));
        d.pollOnce();
        assertThat(pendingFor("replica-c")).isEqualTo(2);
        Thread.sleep(1200);
        d.pollOnce();
        assertThat(pendingFor("replica-c")).isZero();
        assertThat(probe.calls.get()).isEqualTo(5);
        assertThat(valkey.opsForStream().pending("ev:in:probe", "probe").getTotalPendingMessages()).isZero();
    }

    // ---- criterion 3: 8 failures -> DLQ, counter, alert, replay CLI ----

    @Test
    void anEventFailing8TimesIsDeadLetteredCountedAlertedAndReplayedByTheCli() throws Exception {
        double before = meters.get("consumer.dlq").tag("group", "probe").counter().count();
        probe.failing = true;
        String eventId = nextId();
        String entryId = xadd("ev:in:probe", probeEvent(eventId, tenant.id(), UUID.randomUUID(), 1));
        List<ConsumerGroups.Outcome> seen = new ArrayList<>();
        for (int i = 0; i < 20 && !seen.contains(ConsumerGroups.Outcome.DEAD_LETTERED); i++) {
            seen.addAll(groups.pollOnce());
        }
        assertThat(probe.calls.get()).as("tried exactly 8 times").isEqualTo(8);
        assertThat(seen.stream().filter(o -> o == ConsumerGroups.Outcome.FAILED)).hasSize(7);
        assertThat(seen).contains(ConsumerGroups.Outcome.DEAD_LETTERED);
        assertThat(meters.get("consumer.dlq").tag("group", "probe").counter().count()).isEqualTo(before + 1);
        assertThat(valkey.opsForStream().pending("ev:in:probe", "probe").getTotalPendingMessages()).as("acked off the stream").isZero();

        MapRecord<String, Object, Object> dlq = lastDlqEntry();
        assertThat(dlq.getValue()).containsOnlyKeys("originalStream", "entryId", "eventId", "consumer", "attempts", "lastError")
                .containsEntry("originalStream", "ev:in:probe").containsEntry("entryId", entryId).containsEntry("eventId", eventId)
                .containsEntry("consumer", "probe").containsEntry("attempts", "8").containsEntry("lastError", "IllegalStateException");
        assertThat(dlq.getValue().toString()).doesNotContain("must never reach").doesNotContain(tenant.id().toString());

        String scrape = scrape();
        assertThat(scrape).contains("consumer_dlq_total{group=\"probe\"");

        Map<String, Object> rule = alertRule("NabdConsumerDeadLetter");
        assertThat(String.valueOf(rule.get("expr"))).contains("increase(consumer_dlq_total[5m])").contains("> 0");
        assertThat(String.valueOf(rule.get("for"))).isEqualTo("0m");

        // replay: refused without an operator, then applied once the handler is fixed, then the entry is gone
        String dlqId = dlq.getId().getValue();
        assertThat(cli.replay("in", List.of(dlqId), "")).isEqualTo(2);
        probe.failing = false;
        assertThat(cli.replay("in", List.of(dlqId), "oncall.test")).isEqualTo(0);
        assertThat(probe.calls.get()).isEqualTo(9);
        assertThat(ledgerRows(tenant.id(), "probe", eventId)).isEqualTo(1);
        assertThat(valkey.opsForStream().range("ev:in:dlq", Range.closed(dlqId, dlqId))).isEmpty();
        assertThat(cli.replay("in", List.of(dlqId), "oncall.test")).as("already replayed").isEqualTo(1);
        JdbcTemplate su = new JdbcTemplate(superuserDataSource());
        assertThat(su.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'events.dlq.replayed'"
                + " AND actor_name = 'dlq-replay-cli:oncall.test' AND after->>'dlqEntryId' = ? AND after->>'outcome' = 'APPLIED'",
                Long.class, tenant.id(), dlqId)).isEqualTo(1);
        assertThat(su.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id IS NULL AND action = 'events.dlq.replay_failed'"
                + " AND after->>'dlqEntryId' = ?", Long.class, dlqId)).isEqualTo(1);
    }

    @Test
    void aMalformedEnvelopeIsDeadLetteredAtOnceWithoutRetries() {
        String entryId = valkey.opsForStream().add(StreamRecords.newRecord().in("ev:in:probe")
                .ofMap(Map.of(ConsumerGroups.FIELD, "{\"not\":\"an envelope\"}"))).getValue();
        assertThat(drain(groups)).containsOnly(ConsumerGroups.Outcome.DEAD_LETTERED);
        assertThat(probe.calls.get()).isZero();
        assertThat(valkey.opsForStream().range("ev:in:dlq", Range.unbounded()).stream()
                .filter(r -> entryId.equals(r.getValue().get("entryId")))).hasSize(2) // one per probe group
                .allSatisfy(r -> assertThat(r.getValue()).containsEntry("lastError", "malformed_envelope"));
    }

    // ---- criterion 5: lag gauges per group ----

    @Test
    void consumerLagEventsAndSecondsAreExportedPerGroup() throws Exception {
        groups.refreshLag();
        assertThat(lagEvents("probe")).isZero();
        for (int i = 0; i < 3; i++) {
            xadd("ev:in:probe", probeEvent(nextId(), tenant.id(), UUID.randomUUID(), 1));
        }
        Thread.sleep(1100);
        groups.refreshLag();
        assertThat(lagEvents("probe")).isEqualTo(3);
        assertThat(meters.get("consumer.lag.seconds").tags("group", "probe", "stream", "ev:in:probe").gauge().value()).isGreaterThanOrEqualTo(1.0);
        String scrape = scrape();
        assertThat(scrape).contains("consumer_lag_events{group=\"probe\",stream=\"ev:in:probe\"")
                .contains("consumer_lag_seconds{group=\"projector\",stream=\"ev:in:clinical\"")
                .contains("consumer_lag_events{group=\"projector\",stream=\"ev:ksa:clinical\"");

        drain(groups);
        groups.refreshLag();
        assertThat(lagEvents("probe")).isZero();
        assertThat(meters.get("consumer.lag.seconds").tags("group", "probe", "stream", "ev:in:probe").gauge().value()).isZero();
    }

    // ---- framework rules ----

    @Test
    void handlersWithoutAnIdempotencyStrategyOrWithAGateOnValkeyAreRefused() {
        assertThatThrownBy(() -> ConsumerGroups.validate(List.of(new Probe("nope", null))))
                .hasMessageContaining("no idempotency strategy");
        assertThatThrownBy(() -> ConsumerGroups.validate(List.of(new Probe("dup", EventConsumer.Idempotency.DB_LEDGER),
                new Probe("dup", EventConsumer.Idempotency.VALKEY_SET)))).hasMessageContaining("twice");
        Probe gatedOnValkey = new Probe("gated", EventConsumer.Idempotency.VALKEY_SET) {
            public boolean versionGated() { return true; }
        };
        assertThatThrownBy(() -> ConsumerGroups.validate(List.of(gatedOnValkey))).hasMessageContaining("DB_LEDGER");
        assertThatThrownBy(() -> ConsumerGroups.validate(List.of(new Probe("Bad Name", EventConsumer.Idempotency.DB_LEDGER))))
                .hasMessageContaining("group must match");
    }

    @Test
    void shippedDefaultsAndBackoff() {
        ConsumerProperties d = new ConsumerProperties(true, "", List.of("in", "ksa"), 50, 60_000, 8, 1000, 30_000, 86_400);
        assertThat(props.claimIdleMs()).isEqualTo(d.claimIdleMs());
        assertThat(props.maxAttempts()).isEqualTo(8);
        assertThat(props.regions()).containsExactly("in", "ksa");
        ConsumerGroups g = replica("x", d);
        assertThat(List.of(0, 1, 2, 3, 6, 7, 8).stream().map(n -> g.backoff(n).toMillis()))
                .containsExactly(0L, 1000L, 2000L, 4000L, 30_000L, 30_000L, 30_000L);
        assertThat(groups.streams(probe)).containsExactly("ev:in:probe", "ev:ksa:probe");
    }

    // ---- helpers ----

    private ConsumerGroups replica(String name, ConsumerProperties p) {
        return new ConsumerGroups(valkeyProvider, consumers.orderedStream().toList(), ledger, tenantScope, objectMapper, p, meters, clock, name);
    }

    private ConsumerProperties withClaimIdle(long ms) {
        return new ConsumerProperties(false, "", props.regions(), props.batchSize(), ms, props.maxAttempts(), props.retryBaseMs(),
                props.retryMaxMs(), props.appliedTtlSeconds());
    }

    private static List<ConsumerGroups.Outcome> drain(ConsumerGroups g) {
        List<ConsumerGroups.Outcome> all = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            List<ConsumerGroups.Outcome> pass = g.pollOnce();
            if (pass.isEmpty()) {
                break;
            }
            all.addAll(pass);
        }
        all.removeIf(o -> o == ConsumerGroups.Outcome.IGNORED);
        return all;
    }

    private List<MapRecord<String, Object, Object>> readAs(String consumer, int n) {
        return valkey.opsForStream().read(Consumer.from("probe", consumer), StreamReadOptions.empty().count(n),
                StreamOffset.create("ev:in:probe", ReadOffset.lastConsumed()));
    }

    private long pendingFor(String consumer) {
        return valkey.opsForStream().pending("ev:in:probe", Consumer.from("probe", consumer), Range.unbounded(), 100).size();
    }

    private String xadd(String stream, Map<String, Object> envelope) {
        try {
            return valkey.opsForStream().add(StreamRecords.newRecord().in(stream)
                    .ofMap(Map.of(ConsumerGroups.FIELD, objectMapper.writeValueAsString(envelope)))).getValue();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nextId() {
        return String.valueOf(IDS.incrementAndGet());
    }

    private Map<String, Object> envelope(String id, String type, String source, UUID tenantId, UUID subject, Map<String, Object> data) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("specversion", "1.0");
        e.put("id", id);
        e.put("source", source);
        e.put("type", type);
        e.put("time", Instant.now(clock).toString());
        e.put("subject", subject.toString());
        e.put("tenantid", tenantId.toString());
        e.put("region", "in");
        e.put("dataschema", "urn:nabd:event-schema:" + type);
        e.put("datacontenttype", "application/json");
        e.put("data", data);
        return e;
    }

    private Map<String, Object> allergyEvent(String id, UUID patient, long version) {
        return envelope(id, "clinical.allergy.changed.v1", "core/clinical", tenant.id(), patient,
                Map.of("tenantId", tenant.id().toString(), "patientId", patient.toString(), "change", "added", "status", "active", "version", version));
    }

    private Map<String, Object> probeEvent(String id, UUID tenantId, UUID subject, long version) {
        return envelope(id, PROBE, "core/test", tenantId, subject, Map.of("tenantId", tenantId.toString(), "version", version));
    }

    private UUID seedPatientWithAllergy(String substance, String severity) {
        UUID p = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,?,?,'1990-01-01','other') RETURNING id",
                UUID.class, tenant.id(), "Ops Seventeen", String.format("+9197%08d", IDS.incrementAndGet() % 100_000_000L)));
        addAllergy(p, substance, severity);
        return p;
    }

    private void addAllergy(UUID patient, String substance, String severity) {
        inTenantTx(tenant.id(), () -> jdbc.update("INSERT INTO patient_allergies (tenant_id, patient_id, substance, severity, active, recorded_by)"
                + " VALUES (?,?,?,?,true,?)", tenant.id(), patient, substance, severity, staffId));
    }

    private String topAllergy(UUID patient) {
        return inTenantTx(tenant.id(), () -> jdbc.queryForList("SELECT top_allergy FROM patient_registry_summary WHERE patient_id = ?",
                String.class, patient).stream().findFirst().orElse(null));
    }

    private long ledgerRows(UUID tenantId, String group, String eventId) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM projector_applied_events WHERE consumer_group = ? AND event_id = ?", Long.class, group, eventId));
    }

    private MapRecord<String, Object, Object> lastDlqEntry() {
        List<MapRecord<String, Object, Object>> all = valkey.opsForStream().range("ev:in:dlq", Range.unbounded());
        return all.get(all.size() - 1);
    }

    private double lagEvents(String group) {
        return meters.get("consumer.lag.events").tags("group", group, "stream", "ev:in:probe").gauge().value();
    }

    private String scrape() throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + mgmtPort + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static Map<String, Object> alertRule(String name) throws Exception {
        try (InputStream in = Files.newInputStream(Path.of("../infra/observability/event-consumers.rules.yml"))) {
            Map<String, Object> doc = new Yaml().load(in);
            List<Map<String, Object>> rules = (List<Map<String, Object>>) ((List<Map<String, Object>>) doc.get("groups")).get(0).get("rules");
            return rules.stream().filter(r -> name.equals(r.get("alert"))).findFirst().orElseThrow();
        }
    }
}
