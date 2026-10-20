package com.nabd.hms.shared.events.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.Ops020Replica;
import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.common.TenantConfigEvictions;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.common.valkey.ValkeyConfig;
import com.nabd.hms.common.valkey.ValkeyProperties;
import com.nabd.hms.shared.cache.CacheKeyPolicy;
import com.nabd.hms.shared.ops.OutboxMetrics;
import com.nabd.hms.shared.relay.OutboxRelay;
import com.nabd.hms.shared.relay.RelayRedriveCommand;
import com.nabd.hms.shared.relay.RelayProperties;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ValkeyTestSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sprint 12 independent QA (QA-7): OPS-016 relay, OPS-017 consumer groups and the end-to-end path they form with
 * OPS-020 (business action -> outbox -> relay XADD -> consumer group -> projector / cache invalidation), against real
 * Postgres and Redis 6.2 (the Valkey stand-in). Unlike the engineers' tests, the relay here is the REAL context bean
 * (app.relay.enabled=true, shipped timings: poll 2 s, lock retry 2 s) and events come from real HTTP business actions.
 * Consumer polls are driven by hand (pollOnce) so nothing races; extra relays/replicas are built like extra pods.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class S12QaEventPipelineTest extends ApiTestBase {

    static final ValkeyTestSupport VALKEY = ValkeyTestSupport.start();
    static final String QA_TYPE = "qa7.item.changed.v1";
    static final String QA_STREAM = "ev:in:qa7";
    static final String CREATED = DomainEventType.QUEUE_ENTRY_CREATED.type();
    static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000L * 100);
    static final List<LettuceConnectionFactory> FACTORIES = new ArrayList<>();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.valkey.url", VALKEY::url);
        r.add("app.relay.enabled", () -> "true");                 // the real relay bean, shipped timings
        r.add("app.events.consumer.enabled", () -> "false");      // consumers driven by pollOnce()
        r.add("app.events.consumer.name", () -> "qa7-main");
        r.add("app.events.consumer.retry-base-ms", () -> "0");    // DLQ test: retries due at once (curve checked separately)
        r.add("app.cache.tenant-config-ttl-seconds", () -> "30"); // production zone cache (tests default to 0)
        r.add("app.rate-limit.user-per-minute", () -> "100000");
        r.add("app.rate-limit.user-burst", () -> "100000");
        r.add("app.rate-limit.ip-per-minute", () -> "100000");
        r.add("app.rate-limit.ip-burst", () -> "100000");
    }

    @AfterAll
    static void stopValkey() {
        FACTORIES.forEach(LettuceConnectionFactory::destroy);
        VALKEY.close();
    }

    /** A DB-ledger consumer and a Valkey-set consumer on the same QA stream; calls counted, failure on demand. */
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
        public Set<String> types() { return Set.of(QA_TYPE); }
        public Idempotency idempotency() { return idem; }
        public void handle(Event e) {
            calls.incrementAndGet();
            if (failing) {
                throw new IllegalStateException("QA7 secret failure text Asha Rao +919800000000");
            }
        }
    }

    @TestConfiguration
    static class Probes {
        @Bean
        Probe qa7Ledger() { return new Probe("qa7-probe", EventConsumer.Idempotency.DB_LEDGER); }

        @Bean
        Probe qa7Cache() { return new Probe("qa7-cache", EventConsumer.Idempotency.VALKEY_SET); }
    }

    @Autowired ConsumerGroups groups;
    @Autowired OutboxRelay relayBean;
    @Autowired StringRedisTemplate valkey;
    @Autowired ObjectProvider<StringRedisTemplate> valkeyProvider;
    @Autowired ObjectProvider<EventConsumer> consumers;
    @Autowired AppliedEventsRepository ledger;
    @Autowired TenantScope tenantScope;
    @Autowired ObjectMapper objectMapper;
    @Autowired ConsumerProperties consumerProps;
    @Autowired MeterRegistry meters;
    @Autowired Clock clock;
    @Autowired DlqReplayCommand cli;
    @Autowired RelayRedriveCommand redrive;
    @Autowired List<Probe> probes;
    @Autowired Environment env;
    @Autowired EventPublisher publisher;
    @Autowired OutboxMetrics outboxMetrics;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired ClinicClock clinicClock;
    @Autowired TenantConfigEvictions evictions;
    @Autowired CacheKeyPolicy keys;
    @LocalManagementPort int mgmtPort;

    private final ObjectMapper json = new ObjectMapper();
    Probe probe;
    Probe probeCache;

    record U(SeededTenant tenant, SeededStaff staff, String token) {
    }

    @BeforeEach
    void setUp() {
        awaitTrue(30_000, () -> { // Lettuce reconnecting after an outage test
            try {
                valkey.opsForValue().get("qa7:ping");
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        });
        if (!relayBean.isRunning()) {
            relayBean.start();
        }
        probe = probes.stream().filter(p -> p.group.equals("qa7-probe")).findFirst().orElseThrow();
        probeCache = probes.stream().filter(p -> p.group.equals("qa7-cache")).findFirst().orElseThrow();
        probe.failing = false;
        drain(groups);
        probe.calls.set(0);
        probeCache.calls.set(0);
    }

    /** FIX-C: the context (and its relay bean) outlives this class; a relay left leading would claim every later class's domain rows. */
    @AfterEach
    void stopRelay() {
        relayBean.stop();
    }

    // =====================================================================================================
    // OPS-016 AC1: two candidates (three, as a boundary), exactly one leader; the standby publishes nothing
    // =====================================================================================================

    @Test
    void ops016_ac1_ofTwoAndOfThreeCandidatesExactlyOneLeadsAndStandbysPublishNothing() throws Exception {
        SimpleMeterRegistry mb = new SimpleMeterRegistry(), mc = new SimpleMeterRegistry();
        OutboxRelay b = extraRelay(mb, shipped(1_000_000));
        try {
            awaitTrue(8000, () -> relayBean.isLeader() || b.isLeader());
            SeededTenant t = seedTenant();
            double beforeB = mb.counter("relay.events.published").count();
            double beforeA = meters.counter("relay.events.published").count();
            for (int i = 0; i < 12; i++) { // 6 s: three shipped lock-retry periods
                publishOne(t.id());
                Thread.sleep(500);
                double sum = gauge(meters) + gauge(mb);
                assertThat(sum).as("exactly one relay_leader=1 at sample %d", i).isEqualTo(1.0);
                assertThat(relayBean.isLeader() ^ b.isLeader()).isTrue();
            }
            assertThat(heldRelayLocks()).isEqualTo(1);
            awaitTrue(5000, () -> countStatus(t, "done") == 12);
            double pubA = meters.counter("relay.events.published").count() - beforeA;
            double pubB = mb.counter("relay.events.published").count() - beforeB;
            assertThat(relayBean.isLeader() ? pubB : pubA).as("the standby published nothing").isZero();
            assertThat(relayBean.isLeader() ? pubA : pubB).as("the leader published every event").isGreaterThanOrEqualTo(12);

            OutboxRelay c = extraRelay(mc, shipped(1_000_000)); // boundary: a third pod
            Thread.sleep(4500);
            assertThat(gauge(meters) + gauge(mb) + gauge(mc)).isEqualTo(1.0);
            assertThat(heldRelayLocks()).isEqualTo(1);
            c.stop();
        } finally {
            b.stop();
        }
    }

    // =====================================================================================================
    // OPS-016 AC2: leader killed mid-drain (2,000 events in flight) -> standby within 10 s, nothing lost
    // =====================================================================================================

    @Test
    void ops016_ac2_leaderKilledMidDrainStandbyTakesOverWithin10sWithShippedTimingsAndNothingIsLost() throws Exception {
        SimpleMeterRegistry mb = new SimpleMeterRegistry();
        awaitTrue(8000, relayBean::isLeader); // the context bean leads first
        OutboxRelay b = extraRelay(mb, shipped(1_000_000));
        try {
            Thread.sleep(2500);
            assertThat(b.isLeader()).isFalse();
            SeededTenant t = seedTenant();
            publishMany(t, 2000);
            Thread.sleep(150);  // the leader is now draining
            relayBean.stop();   // pod killed: its lock session ends
            long killedAt = System.currentTimeMillis();
            publishMany(t, 30); // committed while nobody leads
            awaitTrue(10_000, b::isLeader);
            long takeover = System.currentTimeMillis() - killedAt;
            System.out.println("QA7 OPS-016 failover ms (shipped lock-retry 2000): " + takeover);
            assertThat(takeover).isLessThan(10_000);
            awaitTrue(25_000, () -> countStatus(t, "done") == 2030); // a batch the dead leader held comes back after its 10 s lease
            Set<Long> inStream = streamIds("ev:in:visit", t.id());
            assertThat(inStream).as("every committed row reached the stream").containsAll(rowIds(t)).hasSize(2030);
        } finally {
            b.stop();
            relayBean.start();
        }
    }

    @Test
    void ops016_ac2_leaderDatabaseSessionTerminatedExactlyOneLeaderAgainWithin10sAndEventsFlow() throws Exception {
        SimpleMeterRegistry mb = new SimpleMeterRegistry();
        OutboxRelay b = extraRelay(mb, shipped(1_000_000));
        try {
            awaitTrue(8000, () -> relayBean.isLeader() || b.isLeader());
            Integer pid = leaderPid();
            assertThat(pid).isNotNull();
            new JdbcTemplate(superuserDataSource()).queryForObject("SELECT pg_terminate_backend(?)", Boolean.class, pid);
            long killedAt = System.currentTimeMillis();
            SeededTenant t = seedTenant();
            publishMany(t, 40);
            awaitTrue(10_000, () -> {
                Integer p = leaderPid();
                return p != null && !p.equals(pid);
            });
            assertThat(System.currentTimeMillis() - killedAt).isLessThan(10_000);
            awaitTrue(10_000, () -> countStatus(t, "done") == 40);
            assertThat(streamIds("ev:in:visit", t.id())).containsAll(rowIds(t));
            Thread.sleep(2500);
            assertThat(gauge(meters) + gauge(mb)).as("still exactly one leader after the killed one reconnected").isEqualTo(1.0);
            assertThat(heldRelayLocks()).isEqualTo(1);
        } finally {
            b.stop();
        }
    }

    // =====================================================================================================
    // OPS-016 AC3: NOTIFY wake p95 < 500 ms for REAL check-ins; < 2.5 s when NOTIFY is lost (shipped 2 s poll)
    // =====================================================================================================

    @Test
    void ops016_ac3_realCheckInsAreXaddedWithin500msP95ByTheNotifyWake() {
        U u = user();
        awaitTrue(8000, relayBean::isLeader);
        List<Long> ms = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            String patientId = patient(u.token);
            ResponseEntity<Map> r = checkIn(u, patientId);
            long committed = System.nanoTime(); // the HTTP response arrives after the commit
            assertThat(r.getStatusCode().value()).as("check-in %s", r.getBody()).isEqualTo(201);
            String entryId = (String) r.getBody().get("id");
            awaitTrue(5000, () -> recentSubjects("ev:in:visit").contains(entryId));
            if (i > 0) { // first one warms the pipelined connection
                ms.add((System.nanoTime() - committed) / 1_000_000);
            }
        }
        Collections.sort(ms);
        long p95 = ms.get((int) Math.ceil(ms.size() * 0.95) - 1);
        System.out.println("QA7 OPS-016 real check-in commit->XADD ms sorted " + ms + " p95=" + p95);
        assertThat(p95).isLessThan(500);
    }

    @Test
    void ops016_ac3_whenNotifyIsLostTheShippedFallbackPollXaddsWithin2500ms() throws Exception {
        SeededTenant t = seedTenant();
        awaitTrue(8000, relayBean::isLeader);
        List<Long> ms = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Thread.sleep(370L * i); // land at different phases of the 2 s poll
            long id;
            try (Connection su = superuserConnection(); Statement st = su.createStatement()) {
                st.execute("SET session_replication_role = replica"); // no trigger -> no NOTIFY (and no version stamp)
                var rs = st.executeQuery("INSERT INTO outbox_events (tenant_id, event_type, payload, ce_type, ce_source, aggregate_id) VALUES ('"
                        + t.id() + "', '" + CREATED + "', '{\"status\":\"waiting\",\"version\":1}', '" + CREATED + "', 'core/visit', gen_random_uuid()) RETURNING id");
                rs.next();
                id = rs.getLong(1);
            }
            long committed = System.nanoTime();
            awaitTrue(4000, () -> "done".equals(status(id)));
            ms.add((System.nanoTime() - committed) / 1_000_000);
        }
        System.out.println("QA7 OPS-016 lost-NOTIFY commit->XADD ms " + ms);
        assertThat(ms).allSatisfy(x -> assertThat(x).isLessThan(2500));
    }

    // =====================================================================================================
    // OPS-016 AC4: ev:in:visit entries of a REAL check-in carry the CloudEvents envelope; MAXLEN ~ trimming
    // =====================================================================================================

    @Test
    void ops016_ac4_aRealCheckInEntryCarriesTheFullPhiFreeEnvelopeInOneFieldCe() throws Exception {
        U u = user();
        String name = "Qa Seven Envelope " + uniqueLetters();
        String phone = phone();
        String patientId = patient(u.token, name, phone);
        String traceparent = "00-" + UUID.randomUUID().toString().replace("-", "") + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "-01";
        ResponseEntity<Map> r = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBodyWithHeader(u.token, "traceparent", traceparent,
                Map.of("patientId", patientId, "doctorId", u.staff.id().toString())), Map.class);
        assertThat(r.getStatusCode().value()).isEqualTo(201);
        String entryId = (String) r.getBody().get("id");
        awaitTrue(5000, () -> recentSubjects("ev:in:visit").contains(entryId));
        MapRecord<String, Object, Object> rec = recent("ev:in:visit").stream()
                .filter(x -> entryId.equals(parse(x).path("subject").asText())).findFirst().orElseThrow();
        assertThat(rec.getValue().keySet()).as("one field, ce").containsExactly("ce");
        String raw = (String) rec.getValue().get("ce");
        JsonNode ce = json.readTree(raw);
        for (String attr : List.of("specversion", "id", "source", "type", "time", "subject", "tenantid", "traceparent", "dataschema", "data")) {
            assertThat(ce.hasNonNull(attr)).as("envelope attribute %s", attr).isTrue();
        }
        assertThat(ce.get("specversion").asText()).isEqualTo("1.0");
        assertThat(ce.get("type").asText()).isEqualTo(CREATED);
        assertThat(ce.get("source").asText()).isEqualTo("core/visit");
        assertThat(ce.get("tenantid").asText()).isEqualTo(u.tenant.id().toString());
        assertThat(ce.get("dataschema").asText()).isEqualTo("urn:nabd:event-schema:" + CREATED);
        assertThat(ce.get("traceparent").asText()).as("request traceparent carried into the stream").isEqualTo(traceparent);
        assertThat(Instant.parse(ce.get("time").asText())).isBefore(Instant.now().plusSeconds(1));
        assertThat(ce.get("data").get("version").isIntegralNumber()).isTrue();
        assertThat(raw.getBytes(StandardCharsets.UTF_8).length).as("envelope < 2 KB").isLessThan(2048);
        assertThat(raw).as("no PHI in the stream").doesNotContain(name).doesNotContain(phone).doesNotContain(phone.substring(3));
        long outboxId = Long.parseLong(ce.get("id").asText());
        String rendered = inTenantTx(u.tenant.id(), () -> jdbc.queryForObject("SELECT envelope::text FROM outbox_cloudevents WHERE id = ?", String.class, outboxId));
        assertThat(ce).isEqualTo(json.readTree(rendered));
        assertThat(status(outboxId)).isEqualTo("done");
    }

    @Test
    void ops016_ac4_maxlenTrimsApproximatelyAndTheShippedValueIsTheDocumentedSevenDays() throws Exception {
        relayBean.stop();
        SimpleMeterRegistry m = new SimpleMeterRegistry();
        OutboxRelay small = extraRelay(m, shipped(50));
        try {
            awaitTrue(8000, small::isLeader);
            SeededTenant t = seedTenant();
            publishMany(t, 600);
            awaitTrue(15_000, () -> countStatus(t, "done") == 600);
            long len = valkey.opsForStream().size("ev:in:visit");
            System.out.println("QA7 OPS-016 XLEN after 600 events with MAXLEN ~ 50: " + len);
            assertThat(len).as("approximate trimming: never below MAXLEN, never far above").isBetween(50L, 200L);
        } finally {
            small.stop();
            relayBean.start();
        }
        RelayProperties shippedDefaults = new RelayProperties(true, 500, 10, 2000, 2000, 1000, 1_000_000, null, null, null);
        assertThat(shippedDefaults.streamMaxlen()).isEqualTo(1_000_000L);
        String doc = Files.readString(Path.of("../docs/architecture/events.md"));
        assertThat(doc).contains("MAXLEN ~ 1,000,000").contains("7 days");
        assertThat(1_000_000 / (7 * 86_400.0)).as("events/s per stream the 7-day sizing assumes").isBetween(1.6, 1.7);
    }

    // =====================================================================================================
    // OPS-016 AC5: Valkey down -> a real check-in still succeeds, its event stays pending, errors count,
    // the oldest-pending alert input passes 30 s, nothing is done without its XADD
    // =====================================================================================================

    @Test
    void ops016_ac5_valkeyDownEventsStayPendingErrorsCountAlertInputFiresAndNothingIsDoneWithoutXadd() throws Exception {
        U u = user();
        String patientId = patient(u.token);
        awaitTrue(8000, relayBean::isLeader);
        double errorsBefore = counter("relay.errors");
        String entryId;
        VALKEY.stop();
        try {
            ResponseEntity<Map> r = checkIn(u, patientId);
            assertThat(r.getStatusCode().value()).as("check-in works with Valkey down").isEqualTo(201);
            entryId = (String) r.getBody().get("id");
            awaitTrue(8000, () -> counter("relay.errors") >= errorsBefore + 2);
            Thread.sleep(1500);
            List<String> st = statusesOf(u.tenant, CREATED);
            assertThat(st).isNotEmpty().allSatisfy(s -> assertThat(s).isIn("pending", "processing"));
            new JdbcTemplate(superuserDataSource()).update(
                    "UPDATE outbox_events SET created_at = now() - interval '31 seconds' WHERE tenant_id = ?", u.tenant.id());
            outboxMetrics.refresh();
            assertThat(meters.get("outbox.oldest.pending.age.seconds").tag("region", "IN").gauge().value())
                    .as("boundary: 31 s > 30 s").isGreaterThan(30);
            String scrape = scrape();
            assertThat(scrape).contains("relay_errors_total").contains("relay_leader").contains("outbox_oldest_pending_age_seconds");
        } finally {
            VALKEY.restart();
        }
        awaitTrue(30_000, () -> statusesOf(u.tenant, CREATED).stream().allMatch("done"::equals));
        awaitTrue(5000, () -> recentSubjects("ev:in:visit").contains(entryId));
        // every done row of this tenant has its stream entry
        assertThat(streamIds("ev:in:visit", u.tenant.id())).containsAll(doneIds(u.tenant));

        List<Map<String, Object>> rules = rules("../infra/observability/outbox-relay.rules.yml");
        Map<String, Object> age = rules.stream().filter(x -> "NabdOutboxOldestPendingOver30s".equals(x.get("alert"))).findFirst().orElseThrow();
        assertThat(String.valueOf(age.get("expr"))).contains("outbox_oldest_pending_age_seconds").contains("> 30");
        assertThat(rules.stream().map(x -> String.valueOf(x.get("expr")))).anyMatch(e -> e.contains("relay_errors_total"));
    }

    // =====================================================================================================
    // OPS-016 AC6: no HTTP client in relay code (source scan on top of RelayArchitectureTest)
    // =====================================================================================================

    @Test
    void ops016_ac6_relaySourcesImportNoHttpClient() throws Exception {
        List<Path> files;
        try (Stream<Path> s = Files.list(Path.of("src/main/java/com/nabd/hms/shared/relay"))) {
            files = s.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(files).isNotEmpty();
        List<String> forbidden = List.of("java.net.http", "java.net.URL", "HttpURLConnection", "org.springframework.web.client",
                "org.springframework.web.reactive", "WebClient", "RestTemplate", "RestClient", "okhttp", "org.apache.http",
                "org.apache.hc", "feign", "com.nabd.hms.shared.integration");
        for (Path f : files) {
            String src = Files.readString(f);
            for (String bad : forbidden) {
                assertThat(src).as("%s must not use %s", f.getFileName(), bad).doesNotContain(bad);
            }
        }
    }

    // =====================================================================================================
    // Tenant isolation of stream data
    // =====================================================================================================

    @Test
    void tenantIsolation_streamEntriesCarryOnlyTheirOwnTenantAndAForgedTenantIdCannotProjectAcrossTenants() throws Exception {
        U a = user();
        U b = user();
        String pa = patient(a.token);
        String pb = patient(b.token);
        String ea = (String) checkIn(a, pa).getBody().get("id");
        String eb = (String) checkIn(b, pb).getBody().get("id");
        awaitTrue(5000, () -> recentSubjects("ev:in:visit").containsAll(List.of(ea, eb)));
        for (MapRecord<String, Object, Object> r : recent("ev:in:visit")) {
            JsonNode ce = parse(r);
            String subject = ce.path("subject").asText();
            if (subject.equals(ea)) {
                assertThat(ce.get("tenantid").asText()).isEqualTo(a.tenant.id().toString());
                assertThat(ce.get("data").get("tenantId").asText()).isEqualTo(a.tenant.id().toString());
            }
            if (subject.equals(eb)) {
                assertThat(ce.get("tenantid").asText()).isEqualTo(b.tenant.id().toString());
            }
            assertThat(ce.path("data").path("tenantId").asText(ce.get("tenantid").asText())).isEqualTo(ce.get("tenantid").asText());
        }
        // a forged entry: tenant B's id but tenant A's patient. The projector runs under B's RLS: A's row is untouched
        addAllergyApi(a, pa, "QaSevenSubstanceA");
        awaitTrue(10_000, () -> {
            drain(groups);
            return "QaSevenSubstanceA".equals(topAllergy(a.tenant.id(), UUID.fromString(pa)));
        });
        drain(groups);
        String forgedId = String.valueOf(SEQ.incrementAndGet());
        xadd("ev:in:clinical", allergyEnvelope(forgedId, b.tenant.id(), UUID.fromString(pa), 9_000_000_000L));
        List<ConsumerGroups.Outcome> out = drain(groups);
        assertThat(out).doesNotContain(ConsumerGroups.Outcome.DEAD_LETTERED);
        assertThat(ledgerRows(a.tenant.id(), "projector", forgedId)).as("no ledger row in tenant A").isZero();
        assertThat(topAllergy(a.tenant.id(), UUID.fromString(pa))).isEqualTo("QaSevenSubstanceA");
        assertThat(inTenantTx(b.tenant.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM patient_registry_summary WHERE patient_id = ?::uuid", Long.class, pa))).as("nothing about A's patient in B").isZero();
    }

    // =====================================================================================================
    // OPS-017 AC1: a relay redelivery (at-least-once) of a real event is a no-op for the projector
    // =====================================================================================================

    @Test
    void ops017_ac1_aRealEventRelayedTwiceIsAppliedOnceByTheProjectorAndOnceByEachProbe() throws Exception {
        U u = user();
        String p = patient(u.token);
        checkIn(u, p);
        addAllergyApi(u, p, "QaSevenDup");
        long id = awaitOutboxDone(u.tenant, "clinical.allergy.changed.v1");
        List<ConsumerGroups.Outcome> first = drain(groups);
        assertThat(first).contains(ConsumerGroups.Outcome.APPLIED);
        assertThat(ledgerRows(u.tenant.id(), "projector", String.valueOf(id))).isEqualTo(1);

        // the relay sends the same row again (e.g. XADD ok but finish lost): back to pending, relay re-XADDs
        new JdbcTemplate(superuserDataSource()).update("UPDATE outbox_events SET status = 'pending', next_attempt_at = now() WHERE id = ?", id);
        awaitTrue(5000, () -> "done".equals(status(id)));
        awaitTrue(5000, () -> streamEntriesWithId("ev:in:clinical", id) == 2);
        List<ConsumerGroups.Outcome> second = drain(groups);
        assertThat(second).containsExactly(ConsumerGroups.Outcome.DUPLICATE);
        assertThat(ledgerRows(u.tenant.id(), "projector", String.valueOf(id))).isEqualTo(1);

        // the same with the probes (handler call counted): DB ledger and Valkey key
        String eid = String.valueOf(SEQ.incrementAndGet());
        Map<String, Object> env = qaEnvelope(eid, u.tenant.id(), UUID.randomUUID(), 1);
        xadd(QA_STREAM, env);
        xadd(QA_STREAM, env);
        drain(groups);
        assertThat(probe.calls.get()).isEqualTo(1);
        assertThat(probeCache.calls.get()).isEqualTo(1);
        assertThat(valkey.opsForStream().pending(QA_STREAM, "qa7-probe").getTotalPendingMessages()).isZero();
    }

    // =====================================================================================================
    // OPS-017 AC2: crash mid-batch. Restart (same name) recovers at once; a peer XAUTOCLAIMs only after the
    // SHIPPED 60 s idle time (boundary: not at 55 s, yes at 61 s)
    // =====================================================================================================

    @Test
    void ops017_ac2_crashMidBatchRecoveredByRestartAtOnceAndByAPeerOnlyAfterTheShipped60s() throws Exception {
        SeededTenant t = seedTenant();
        // restart path
        ensureGroups();
        for (int i = 0; i < 3; i++) {
            xadd(QA_STREAM, qaEnvelope(String.valueOf(SEQ.incrementAndGet()), t.id(), UUID.randomUUID(), 1));
        }
        List<MapRecord<String, Object, Object>> batch = readAs("qa7-a", 3);
        assertThat(batch).hasSize(3);
        assertThat(replica("qa7-a", consumerProps).deliver(valkey, probe, batch.get(0))).isEqualTo(ConsumerGroups.Outcome.APPLIED);
        // crash: 2 delivered, never acked
        assertThat(pendingFor("qa7-a")).isEqualTo(2);
        replica("qa7-a", consumerProps).pollOnce(); // restarted pod, same consumer name
        assertThat(pendingFor("qa7-a")).isZero();
        assertThat(probe.calls.get()).isEqualTo(3);

        // peer path with the shipped claim-idle time
        assertThat(consumerProps.claimIdleMs()).isEqualTo(60_000);
        for (int i = 0; i < 2; i++) {
            xadd(QA_STREAM, qaEnvelope(String.valueOf(SEQ.incrementAndGet()), t.id(), UUID.randomUUID(), 1));
        }
        assertThat(readAs("qa7-dead", 2)).hasSize(2);
        long crashedAt = System.currentTimeMillis();
        ConsumerGroups peer = replica("qa7-peer", consumerProps);
        peer.pollOnce();
        assertThat(pendingFor("qa7-dead")).isEqualTo(2);
        Thread.sleep(Math.max(0, 55_000 - (System.currentTimeMillis() - crashedAt)));
        peer.pollOnce();
        assertThat(pendingFor("qa7-dead")).as("55 s idle: not yet").isEqualTo(2);
        assertThat(probe.calls.get()).isEqualTo(3);
        Thread.sleep(Math.max(0, 61_000 - (System.currentTimeMillis() - crashedAt)));
        peer.pollOnce();
        assertThat(pendingFor("qa7-dead")).as("61 s idle: claimed").isZero();
        assertThat(probe.calls.get()).isEqualTo(5);
        assertThat(valkey.opsForStream().pending(QA_STREAM, "qa7-probe").getTotalPendingMessages()).isZero();
    }

    // =====================================================================================================
    // OPS-017 AC3: 7 failures -> still pending (boundary); 8th -> DLQ, counter, alert, CLI replay (all exit codes)
    // =====================================================================================================

    @Test
    void ops017_ac3_sevenFailuresStayPendingTheEighthDeadLettersCountsAlertsAndTheCliReplaysWithAudit() throws Exception {
        SeededTenant t = seedTenant();
        double before = meters.get("consumer.dlq").tag("group", "qa7-probe").counter().count();
        probe.failing = true;
        String eventId = String.valueOf(SEQ.incrementAndGet());
        String entryId = xadd(QA_STREAM, qaEnvelope(eventId, t.id(), UUID.randomUUID(), 1));
        List<ConsumerGroups.Outcome> seen = new ArrayList<>();
        for (int i = 0; i < 40 && probe.calls.get() < 7; i++) {
            seen.addAll(groups.pollOnce());
        }
        assertThat(probe.calls.get()).isEqualTo(7);
        assertThat(seen).doesNotContain(ConsumerGroups.Outcome.DEAD_LETTERED);
        assertThat(dlqFor(entryId)).as("7 failures: not dead-lettered yet").isEmpty();
        assertThat(valkey.opsForStream().pending(QA_STREAM, "qa7-probe").getTotalPendingMessages()).isEqualTo(1);
        for (int i = 0; i < 40 && !seen.contains(ConsumerGroups.Outcome.DEAD_LETTERED); i++) {
            seen.addAll(groups.pollOnce());
        }
        assertThat(probe.calls.get()).as("8 attempts in total").isEqualTo(8);
        assertThat(seen).contains(ConsumerGroups.Outcome.DEAD_LETTERED);
        List<MapRecord<String, Object, Object>> dlq = dlqFor(entryId);
        assertThat(dlq).hasSize(1);
        Map<Object, Object> f = dlq.get(0).getValue();
        assertThat(f).containsEntry("originalStream", QA_STREAM).containsEntry("eventId", eventId).containsEntry("consumer", "qa7-probe")
                .containsEntry("attempts", "8").containsEntry("lastError", "IllegalStateException");
        assertThat(f.toString()).doesNotContain("Asha").doesNotContain("+9198").doesNotContain("secret").doesNotContain(t.id().toString());
        assertThat(meters.get("consumer.dlq").tag("group", "qa7-probe").counter().count()).isEqualTo(before + 1);
        assertThat(scrape()).contains("consumer_dlq_total{group=\"qa7-probe\"");
        assertThat(valkey.opsForStream().pending(QA_STREAM, "qa7-probe").getTotalPendingMessages()).isZero();
        Map<String, Object> rule = rules("../infra/observability/event-consumers.rules.yml").stream()
                .filter(r -> "NabdConsumerDeadLetter".equals(r.get("alert"))).findFirst().orElseThrow();
        assertThat(String.valueOf(rule.get("expr"))).contains("consumer_dlq_total");

        String dlqId = dlq.get(0).getId().getValue();
        assertThat(cli.replay("in", List.of(dlqId), "x")).as("operator name too short").isEqualTo(2);
        assertThat(cli.replay("in", List.of(dlqId), null)).as("no operator").isEqualTo(2);
        assertThat(cli.replay("zz", List.of(dlqId), "qa7.oncall")).as("unknown region").isEqualTo(1);
        assertThat(cli.replay("in", List.of("not-an-id"), "qa7.oncall")).as("malformed id").isEqualTo(1);
        assertThat(probe.calls.get()).isEqualTo(8);
        probe.failing = false;
        assertThat(cli.replay("in", List.of(dlqId), "qa7.oncall")).isEqualTo(0);
        assertThat(probe.calls.get()).isEqualTo(9);
        assertThat(dlqFor(entryId)).isEmpty();
        assertThat(cli.replay("in", List.of(dlqId), "qa7.oncall")).as("replayed already").isEqualTo(1);
        JdbcTemplate su = new JdbcTemplate(superuserDataSource());
        assertThat(su.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'events.dlq.replayed'"
                + " AND actor_name = 'dlq-replay-cli:qa7.oncall'", Long.class, t.id())).isEqualTo(1);
        assertThat(su.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id IS NULL AND action = 'events.dlq.replay_failed'"
                + " AND actor_name = 'dlq-replay-cli:qa7.oncall' AND created_at > now() - interval '5 minutes'", Long.class)).isGreaterThanOrEqualTo(3);
    }

    @Test
    void ops017_ac3_aPoisonEventForTheRealProjectorIsDeadLetteredAfter8Tries() {
        SeededTenant t = seedTenant();
        Map<String, Object> env = allergyEnvelope(String.valueOf(SEQ.incrementAndGet()), t.id(), UUID.randomUUID(), 5);
        ((Map<String, Object>) env.get("data")).put("patientId", "not-a-uuid");
        String entryId = xadd("ev:in:clinical", env);
        List<ConsumerGroups.Outcome> seen = new ArrayList<>();
        for (int i = 0; i < 40 && !seen.contains(ConsumerGroups.Outcome.DEAD_LETTERED); i++) {
            seen.addAll(groups.pollOnce());
        }
        assertThat(seen.stream().filter(o -> o == ConsumerGroups.Outcome.FAILED)).hasSize(7);
        assertThat(dlqFor(entryId)).hasSize(1).allSatisfy(r -> assertThat(r.getValue())
                .containsEntry("consumer", "projector").containsEntry("lastError", "IllegalArgumentException").containsEntry("attempts", "8"));
    }

    // =====================================================================================================
    // OPS-017 AC4: version gate (v=5 then v=4 ignored; equal version ignored; other subject unaffected;
    // real events delivered out of order)
    // =====================================================================================================

    @Test
    void ops017_ac4_versionGateIgnoresLowerAndEqualVersionsPerSubjectIncludingRealEventsOutOfOrder() throws Exception {
        U u = user();
        String p = patient(u.token);
        String other = patient(u.token);
        checkIn(u, p);
        checkIn(u, other);
        UUID pid = UUID.fromString(p), oid = UUID.fromString(other);
        xadd("ev:in:clinical", allergyEnvelope(next(), u.tenant.id(), pid, 5));
        assertThat(drain(groups)).containsExactly(ConsumerGroups.Outcome.APPLIED);
        xadd("ev:in:clinical", allergyEnvelope(next(), u.tenant.id(), pid, 4));
        assertThat(drain(groups)).containsExactly(ConsumerGroups.Outcome.STALE);
        xadd("ev:in:clinical", allergyEnvelope(next(), u.tenant.id(), pid, 5));
        assertThat(drain(groups)).as("boundary: equal version").containsExactly(ConsumerGroups.Outcome.STALE);
        xadd("ev:in:clinical", allergyEnvelope(next(), u.tenant.id(), oid, 4));
        assertThat(drain(groups)).as("another subject is gated separately").containsExactly(ConsumerGroups.Outcome.APPLIED);

        // real events: two allergy changes of one patient, re-delivered newest first
        String q = patient(u.token);
        checkIn(u, q);
        relayBean.stop(); // keep them in the outbox, then deliver in reverse order by hand
        try {
            addAllergyApi(u, q, "QaSevenOld");
            addAllergyApi(u, q, "QaSevenNew");
            List<String> envs = inTenantTx(u.tenant.id(), () -> jdbc.queryForList(
                    "SELECT envelope::text FROM outbox_cloudevents WHERE envelope->>'subject' = ? AND envelope->>'type' = 'clinical.allergy.changed.v1' ORDER BY id",
                    String.class, q));
            assertThat(envs).hasSize(2);
            xaddRaw("ev:in:clinical", envs.get(1));
            assertThat(drain(groups)).containsExactly(ConsumerGroups.Outcome.APPLIED);
            xaddRaw("ev:in:clinical", envs.get(0));
            assertThat(drain(groups)).as("older real event after the newer one").containsExactly(ConsumerGroups.Outcome.STALE);
        } finally {
            relayBean.start();
        }
    }

    // =====================================================================================================
    // OPS-017 AC5: lag per group (two groups on one stream diverge)
    // =====================================================================================================

    @Test
    void ops017_ac5_lagEventsAndSecondsAreExportedPerGroupAndDifferBetweenGroups() throws Exception {
        SeededTenant t = seedTenant();
        ensureGroups();
        groups.refreshLag();
        for (int i = 0; i < 5; i++) {
            xadd(QA_STREAM, qaEnvelope(next(), t.id(), UUID.randomUUID(), 1));
        }
        Thread.sleep(1200);
        groups.refreshLag();
        assertThat(lag("consumer.lag.events", "qa7-probe")).isEqualTo(5);
        assertThat(lag("consumer.lag.events", "qa7-cache")).isEqualTo(5);
        assertThat(lag("consumer.lag.seconds", "qa7-probe")).isGreaterThanOrEqualTo(1.0);
        // only qa7-probe is delivered to: its lag goes to 0, qa7-cache stays behind
        List<MapRecord<String, Object, Object>> got = valkey.opsForStream().read(Consumer.from("qa7-probe", "qa7-lagger"),
                StreamReadOptions.empty().count(10), StreamOffset.create(QA_STREAM, ReadOffset.lastConsumed()));
        assertThat(got).hasSize(5);
        groups.refreshLag();
        assertThat(lag("consumer.lag.events", "qa7-probe")).isZero();
        assertThat(lag("consumer.lag.seconds", "qa7-probe")).isZero();
        assertThat(lag("consumer.lag.events", "qa7-cache")).isEqualTo(5);
        String scrape = scrape();
        assertThat(scrape).contains("consumer_lag_events{group=\"qa7-cache\",stream=\"ev:in:qa7\"} 5.0")
                .contains("consumer_lag_seconds{group=\"qa7-probe\",stream=\"ev:in:qa7\"}")
                .contains("consumer_lag_events{group=\"projector\",stream=\"ev:in:clinical\"}");
        drain(groups);
        replica("qa7-lagger", consumerProps).pollOnce(); // ack the hand-read ones
        Map<String, Object> lagRule = rules("../infra/observability/event-consumers.rules.yml").stream()
                .filter(r -> "NabdConsumerLagging".equals(r.get("alert"))).findFirst().orElseThrow();
        assertThat(String.valueOf(lagRule.get("expr"))).contains("consumer_lag_seconds");
    }

    @Test
    void ops017_nfr_aConsumerWithoutIdempotencyStrategyIsRefused() {
        assertThatThrownBy(() -> ConsumerGroups.validate(List.of(new Probe("qa7-none", null)))).hasMessageContaining("idempotency");
        ConsumerGroups d = replica("x", new ConsumerProperties(true, "", List.of("in", "ksa"), 50, 60_000, 8, 1000, 30_000, 86_400));
        assertThat(d.backoff(1).toMillis()).isEqualTo(1000);
        assertThat(d.backoff(7).toMillis()).isEqualTo(30_000);
    }

    // =====================================================================================================
    // END TO END: real allergy POST -> outbox -> relay XADD -> projector group -> registry projection
    // =====================================================================================================

    @Test
    void e2e_allergyRecordedViaApiReachesTheProjectorGroupThroughTheRelay() {
        U u = user();
        String p = patient(u.token);
        checkIn(u, p);
        long t0 = System.nanoTime();
        addAllergyApi(u, p, "QaSevenE2e");
        long id = awaitOutboxDone(u.tenant, "clinical.allergy.changed.v1");
        assertThat(streamEntriesWithId("ev:in:clinical", id)).isEqualTo(1);
        List<ConsumerGroups.Outcome> out = drain(groups);
        System.out.println("QA7 E2E allergy POST -> projected ms: " + (System.nanoTime() - t0) / 1_000_000);
        assertThat(out).contains(ConsumerGroups.Outcome.APPLIED);
        assertThat(ledgerRows(u.tenant.id(), "projector", String.valueOf(id))).as("applied by the projector consumer group").isEqualTo(1);
        assertThat(topAllergy(u.tenant.id(), UUID.fromString(p))).isEqualTo("QaSevenE2e");
        assertThat(valkey.opsForStream().pending("ev:in:clinical", "projector").getTotalPendingMessages()).isZero();
    }

    @Test
    void e2e_valkeyStoppedBeforeTheXaddAndRestartedEmptyNoEventLostNoDuplicateEffect() throws Exception {
        U u = user();
        String p = patient(u.token);
        checkIn(u, p);
        addAllergyApi(u, p, "QaSevenBefore");
        long first = awaitOutboxDone(u.tenant, "clinical.allergy.changed.v1");
        drain(groups);
        assertThat(ledgerRows(u.tenant.id(), "projector", String.valueOf(first))).isEqualTo(1);

        VALKEY.stop();
        String p2;
        try {
            addAllergyApi(u, p, "QaSevenDuring"); // the business write succeeds with Valkey down
            p2 = patient(u.token);
            assertThat(checkIn(u, p2).getStatusCode().value()).isEqualTo(201);
            assertThat(groups.pollOnce()).as("consumers degrade quietly").isEmpty();
            Thread.sleep(2500);
            assertThat(statusesOf(u.tenant, "clinical.allergy.changed.v1")).as("nothing done without XADD").contains("done")
                    .anySatisfy(s -> assertThat(s).isIn("pending", "processing"));
        } finally {
            VALKEY.restart(); // empty: streams and consumer groups are gone
        }
        awaitTrue(30_000, () -> statusesOf(u.tenant, "clinical.allergy.changed.v1").stream().allMatch("done"::equals));
        long during = lastOutboxId(u.tenant, "clinical.allergy.changed.v1");
        List<ConsumerGroups.Outcome> out = new ArrayList<>();
        awaitTrue(15_000, () -> {
            out.addAll(groups.pollOnce()); // first pass may hit NOGROUP and recreate the group
            return ledgerRows(u.tenant.id(), "projector", String.valueOf(during)) == 1;
        });
        assertThat(topAllergy(u.tenant.id(), UUID.fromString(p))).isNotNull();
        for (int i = 0; i < 5; i++) {
            out.addAll(groups.pollOnce());
        }
        assertThat(ledgerRows(u.tenant.id(), "projector", String.valueOf(during))).as("exactly once in effect").isEqualTo(1);
        assertThat(ledgerRows(u.tenant.id(), "projector", String.valueOf(first))).isEqualTo(1);
        assertThat(valkey.opsForStream().pending("ev:in:clinical", "projector").getTotalPendingMessages()).isZero();
    }

    /**
     * Valkey restarts (empty, as a failover without persistence) after the relay XADDed but before the group read it.
     * FIX-C (D2): production keeps the streams durable (ElastiCache durability sync, valkey.tf), but the stand-in restarts empty by
     * design, so the entry IS gone here; what must hold is that on-call's redrive of the window recovers it, exactly once.
     */
    @Test
    void e2e_valkeyRestartedAfterTheXaddButBeforeTheConsumerReadLosesNoEvent() throws Exception {
        Instant windowFrom = Instant.now().minusSeconds(5);
        U u = user();
        String p = patient(u.token);
        checkIn(u, p);
        drain(groups);
        addAllergyApi(u, p, "QaSevenInFlight");
        long id = awaitOutboxDone(u.tenant, "clinical.allergy.changed.v1"); // XADDed, row done
        VALKEY.stop();
        VALKEY.restart();
        awaitTrue(30_000, () -> {
            try {
                valkey.opsForValue().get("qa7:ping");
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        });
        assertThat(streamEntriesWithId("ev:in:clinical", id)).as("the stand-in lost the entry").isZero();
        String to = Instant.now().plusSeconds(5).toString();
        assertThat(redrive.redrive(windowFrom.toString(), to, "x")).as("operator required").isEqualTo(2);
        assertThat(redrive.redrive(to, windowFrom.toString(), "qa7-oncall")).as("inverted window").isEqualTo(2);
        assertThat(redrive.redrive(windowFrom.minusSeconds(8 * 86400).toString(), to, "qa7-oncall")).as("over 7 days").isEqualTo(2);
        assertThat(redrive.redrive(windowFrom.toString(), to, "qa7-oncall")).isZero();
        assertThat(new JdbcTemplate(superuserDataSource()).queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id IS NULL "
                + "AND action = 'events.relay.redriven' AND actor_name = 'relay-redrive-cli:qa7-oncall'", Integer.class)).isPositive();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && ledgerRows(u.tenant.id(), "projector", String.valueOf(id)) == 0) {
            groups.pollOnce();
            Thread.sleep(200);
        }
        System.out.println("QA7 E2E in-flight event after empty Valkey restart: outbox status=" + status(id)
                + " stream entries=" + streamEntriesWithId("ev:in:clinical", id)
                + " ledger=" + ledgerRows(u.tenant.id(), "projector", String.valueOf(id)));
        assertThat(ledgerRows(u.tenant.id(), "projector", String.valueOf(id))).as("event consumed despite the Valkey restart").isEqualTo(1);
        for (int i = 0; i < 3; i++) {
            groups.pollOnce();
        }
        assertThat(ledgerRows(u.tenant.id(), "projector", String.valueOf(id))).as("exactly once in effect").isEqualTo(1);
        assertThat(status(id)).isEqualTo("done");
    }

    // =====================================================================================================
    // END TO END (OPS-020 AC4 in a Valkey region): timezone PATCH -> organization.config.changed.v1 ->
    // every instance on the new zone within 5 s. Dispatcher and relay both run, as in production.
    // =====================================================================================================

    @Test
    void e2e_ops020_ac4_timezoneChangeReachesEveryInstanceWithin5sInARegionWithStreams() throws Exception {
        U a = user();
        U other = user();
        StringRedisTemplate tB = replicaTemplate();
        ClinicClock clockB = Ops020Replica.clinicClock(jdbc, keys, 30);
        TenantConfigEvictions evictionsB = new TenantConfigEvictions(clockB, provider(StringRedisTemplate.class, tB),
                provider(RedisConnectionFactory.class, tB.getConnectionFactory()));
        evictionsB.ensureSubscribed();
        evictions.ensureSubscribed();
        ZoneId before = clinicClock.zone(a.tenant.id());
        ZoneId otherBefore = clinicClock.zone(other.tenant.id());
        assertThat(clockB.zone(a.tenant.id())).isEqualTo(before);
        String newZone = before.getId().equals("Pacific/Chatham") ? "Pacific/Apia" : "Pacific/Chatham";
        ResponseEntity<Map> patch = exchange("/v1/setup/profile", HttpMethod.PATCH,
                authedJsonBody(a.token, Map.of("name", "Qa7 Clinic", "timezone", newZone)), Map.class);
        assertThat(patch.getStatusCode().value()).as("profile %s", patch.getBody()).isEqualTo(200);
        long t0 = System.nanoTime();
        while (System.nanoTime() - t0 < 5_000_000_000L
                && !(clinicClock.zone(a.tenant.id()).getId().equals(newZone) && clockB.zone(a.tenant.id()).getId().equals(newZone))) {
            dispatcher.poll();   // the API pods' outbox dispatcher (scheduled in production)
            drain(groups);       // every consumer group there is
            Thread.sleep(200);
        }
        List<String> st = statusesOf(a.tenant, "organization.config.changed.v1");
        System.out.println("QA7 E2E tz change after 5 s: A=" + clinicClock.zone(a.tenant.id()) + " B=" + clockB.zone(a.tenant.id())
                + " outbox=" + st + " ev:in:organization entries=" + recentSubjects("ev:in:organization").contains(a.tenant.id().toString()));
        assertThat(clinicClock.zone(other.tenant.id())).as("tenant isolation: another tenant keeps its zone").isEqualTo(otherBefore);
        assertThat(clinicClock.zone(a.tenant.id()).getId()).as("instance A on the new zone within 5 s").isEqualTo(newZone);
        assertThat(clockB.zone(a.tenant.id()).getId()).as("instance B on the new zone within 5 s").isEqualTo(newZone);
    }

    // =====================================================================================================
    // helpers
    // =====================================================================================================

    private RelayProperties shipped(long maxlen) {
        return new RelayProperties(true, 500, 10, 2000, 2000, 1000, maxlen, null, null, null);
    }

    private OutboxRelay extraRelay(SimpleMeterRegistry m, RelayProperties p) {
        OutboxRelay r = new OutboxRelay(jdbc, valkey, p, OutboxRelay.direct(env.getProperty("spring.datasource.url"),
                env.getProperty("spring.datasource.username"), env.getProperty("spring.datasource.password")), m);
        r.start();
        return r;
    }

    private static double gauge(MeterRegistry m) {
        return m.get("relay.leader").gauge().value();
    }

    private double counter(String name) {
        Counter c = meters.find(name).counter();
        return c == null ? 0 : c.count();
    }

    private static long heldRelayLocks() {
        return new JdbcTemplate(superuserDataSource()).queryForObject("""
                SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid
                WHERE l.locktype = 'advisory' AND l.granted AND a.application_name = 'nabd-outbox-relay'""", Long.class);
    }

    private static Integer leaderPid() {
        List<Integer> p = new JdbcTemplate(superuserDataSource()).queryForList("""
                SELECT l.pid FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid
                WHERE l.locktype = 'advisory' AND l.granted AND a.application_name = 'nabd-outbox-relay'""", Integer.class);
        return p.isEmpty() ? null : p.get(0);
    }

    private static void awaitTrue(long ms, BooleanSupplier cond) {
        long deadline = System.currentTimeMillis() + ms;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within " + ms + " ms");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    private void publishOne(UUID tenant) {
        UUID entry = UUID.randomUUID();
        inTenantTx(tenant, () -> publisher.publish(tenant, DomainEventType.QUEUE_ENTRY_CREATED, entry,
                Map.of("entryId", entry, "doctorId", UUID.randomUUID(), "queueDate", "2026-10-20", "status", "waiting")));
    }

    private void publishMany(SeededTenant t, int n) {
        inTenantTx(t.id(), () -> jdbc.update("""
                INSERT INTO outbox_events (tenant_id, event_type, payload, ce_type, ce_source, aggregate_id)
                SELECT ?, ?, jsonb_build_object('tenantId', ?::text, 'entryId', gen_random_uuid(), 'status', 'waiting'), ?, 'core/visit',
                       gen_random_uuid()
                FROM generate_series(1, ?)""", t.id(), CREATED, t.id().toString(), CREATED, n));
    }

    private static long countStatus(SeededTenant t, String status) {
        return new JdbcTemplate(superuserDataSource()).queryForObject(
                "SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND status = ?", Long.class, t.id(), status);
    }

    private static String status(long id) {
        return new JdbcTemplate(superuserDataSource()).queryForObject("SELECT status FROM outbox_events WHERE id = ?", String.class, id);
    }

    private static List<String> statusesOf(SeededTenant t, String type) {
        return new JdbcTemplate(superuserDataSource()).queryForList(
                "SELECT status FROM outbox_events WHERE tenant_id = ? AND event_type = ? ORDER BY id", String.class, t.id(), type);
    }

    private static long lastOutboxId(SeededTenant t, String type) {
        return new JdbcTemplate(superuserDataSource()).queryForObject(
                "SELECT max(id) FROM outbox_events WHERE tenant_id = ? AND event_type = ?", Long.class, t.id(), type);
    }

    private static long awaitOutboxDone(SeededTenant t, String type) {
        awaitTrue(10_000, () -> {
            List<String> s = statusesOf(t, type);
            return !s.isEmpty() && s.stream().allMatch("done"::equals);
        });
        return lastOutboxId(t, type);
    }

    private static Set<Long> rowIds(SeededTenant t) {
        return new HashSet<>(new JdbcTemplate(superuserDataSource()).queryForList(
                "SELECT id FROM outbox_events WHERE tenant_id = ? AND ce_type IS NOT NULL", Long.class, t.id()));
    }

    private static Set<Long> doneIds(SeededTenant t) {
        return new HashSet<>(new JdbcTemplate(superuserDataSource()).queryForList(
                "SELECT id FROM outbox_events WHERE tenant_id = ? AND ce_type = ? AND status = 'done'", Long.class, t.id(), CREATED));
    }

    private JsonNode parse(MapRecord<String, Object, Object> r) {
        try {
            return json.readTree((String) r.getValue().get("ce"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private List<MapRecord<String, Object, Object>> recent(String stream) {
        List<MapRecord<String, Object, Object>> r = valkey.opsForStream().reverseRange(stream, Range.unbounded(), Limit.limit().count(200));
        return r == null ? List.of() : r;
    }

    private Set<String> recentSubjects(String stream) {
        Set<String> s = new HashSet<>();
        recent(stream).forEach(r -> s.add(parse(r).path("subject").asText()));
        return s;
    }

    private Set<Long> streamIds(String stream, UUID tenant) {
        Set<Long> ids = new HashSet<>();
        List<MapRecord<String, Object, Object>> all = valkey.opsForStream().range(stream, Range.unbounded());
        for (MapRecord<String, Object, Object> r : all == null ? List.<MapRecord<String, Object, Object>>of() : all) {
            JsonNode ce = parse(r);
            if (tenant.toString().equals(ce.path("tenantid").asText())) {
                ids.add(Long.parseLong(ce.get("id").asText()));
            }
        }
        return ids;
    }

    private long streamEntriesWithId(String stream, long id) {
        List<MapRecord<String, Object, Object>> all = valkey.opsForStream().range(stream, Range.unbounded());
        return all == null ? 0 : all.stream().filter(r -> String.valueOf(id).equals(parse(r).path("id").asText())).count();
    }

    private String xadd(String stream, Map<String, Object> envelope) {
        try {
            return xaddRaw(stream, objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String xaddRaw(String stream, String envelope) {
        return valkey.opsForStream().add(StreamRecords.newRecord().in(stream).ofMap(Map.of(ConsumerGroups.FIELD, envelope))).getValue();
    }

    private static String next() {
        return String.valueOf(SEQ.incrementAndGet());
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

    private Map<String, Object> allergyEnvelope(String id, UUID tenant, UUID patient, long version) {
        Map<String, Object> data = new LinkedHashMap<>(Map.of("tenantId", tenant.toString(), "patientId", patient.toString(),
                "change", "added", "status", "active", "version", version));
        return envelope(id, "clinical.allergy.changed.v1", "core/clinical", tenant, patient, data);
    }

    private Map<String, Object> qaEnvelope(String id, UUID tenant, UUID subject, long version) {
        return envelope(id, QA_TYPE, "core/test", tenant, subject, new LinkedHashMap<>(Map.of("tenantId", tenant.toString(), "version", version)));
    }

    private ConsumerGroups replica(String name, ConsumerProperties p) {
        return new ConsumerGroups(valkeyProvider, consumers.orderedStream().toList(), ledger, tenantScope, objectMapper, p, meters, clock, name);
    }

    private void ensureGroups() {
        groups.pollOnce(); // creates every group (MKSTREAM) on first pass
    }

    private List<MapRecord<String, Object, Object>> readAs(String consumer, int n) {
        return valkey.opsForStream().read(Consumer.from("qa7-probe", consumer), StreamReadOptions.empty().count(n),
                StreamOffset.create(QA_STREAM, ReadOffset.lastConsumed()));
    }

    private long pendingFor(String consumer) {
        return valkey.opsForStream().pending(QA_STREAM, Consumer.from("qa7-probe", consumer), Range.unbounded(), 100).size();
    }

    private List<MapRecord<String, Object, Object>> dlqFor(String entryId) {
        List<MapRecord<String, Object, Object>> all = valkey.opsForStream().range("ev:in:dlq", Range.unbounded());
        return all == null ? List.of() : all.stream().filter(r -> entryId.equals(r.getValue().get("entryId"))).toList();
    }

    private double lag(String metric, String group) {
        return meters.get(metric).tags("group", group, "stream", QA_STREAM).gauge().value();
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

    private long ledgerRows(UUID tenantId, String group, String eventId) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM projector_applied_events WHERE consumer_group = ? AND event_id = ?", Long.class, group, eventId));
    }

    private String topAllergy(UUID tenant, UUID patient) {
        return inTenantTx(tenant, () -> jdbc.queryForList("SELECT top_allergy FROM patient_registry_summary WHERE patient_id = ?",
                String.class, patient).stream().findFirst().orElse(null));
    }

    private String scrape() throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + mgmtPort + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static List<Map<String, Object>> rules(String file) throws Exception {
        try (InputStream in = Files.newInputStream(Path.of(file))) {
            Map<String, Object> doc = new Yaml().load(in);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> g : (List<Map<String, Object>>) doc.get("groups")) {
                out.addAll((List<Map<String, Object>>) g.get("rules"));
            }
            return out;
        }
    }

    static StringRedisTemplate replicaTemplate() {
        LettuceConnectionFactory f = new ValkeyConfig().valkeyConnectionFactory(new ValkeyProperties(VALKEY.url(), 500, 250), new MockEnvironment());
        f.afterPropertiesSet();
        f.start();
        FACTORIES.add(f);
        return new StringRedisTemplate(f);
    }

    static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
        return new StaticListableBeanFactory(Map.of("b", bean)).getBeanProvider(type);
    }

    // ---- business fixtures (real HTTP) ----

    static String uniqueLetters() { // registration refuses a second name+dob match: fresh letters each time
        StringBuilder b = new StringBuilder();
        for (char c : UUID.randomUUID().toString().replace("-", "").substring(0, 10).toCharArray()) {
            b.append((char) ('a' + Character.digit(c, 16)));
        }
        return b.toString();
    }

    private static String phone() {
        return "+9198" + String.format("%08d", Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000_000L));
    }

    private U user() {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "qa7-" + UUID.randomUUID().toString().substring(0, 8) + "@qa7.test", phone(), false);
        return new U(t, s, loginAndGetAccessToken(s));
    }

    private String patient(String token) {
        return patient(token, "Qa Seven " + uniqueLetters(), phone());
    }

    private String patient(String token, String name, String phone) {
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", java.time.LocalDate.of(1950, 1, 1)
                        .plusDays(Math.abs(UUID.randomUUID().getLeastSignificantBits() % 20000)).toString(), "gender", "female")), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("patient: %s %s", r.getStatusCode(), r.getBody()).isTrue();
        return (String) r.getBody().get("id");
    }

    private ResponseEntity<Map> checkIn(U u, String patientId) {
        return exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(u.token, Map.of(
                "patientId", patientId, "doctorId", u.staff.id().toString())), Map.class);
    }

    private void addAllergyApi(U u, String patientId, String substance) {
        ResponseEntity<Map> r = exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.POST,
                authedJsonBody(u.token, Map.of("substance", substance, "severity", "severe")), Map.class);
        assertThat(r.getStatusCode().value()).as("allergy: %s", r.getBody()).isEqualTo(201);
    }
}
