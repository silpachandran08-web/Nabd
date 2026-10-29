package com.nabd.comms.quality;

import com.nabd.comms.provider.Provider;
import com.nabd.comms.quality.QualityPolicy.Mode;
import com.nabd.comms.send.JdbcOutcomeStore;
import com.nabd.comms.send.Lane;
import com.nabd.comms.send.LaneScheduler;
import com.nabd.comms.send.SenderLoop;
import com.nabd.comms.send.StatusApplier;
import com.nabd.comms.send.TokenBucket;
import com.nabd.comms.testkit.Infra;
import com.nabd.comms.testkit.ProviderStub;
import com.nabd.comms.testkit.ProviderStub.WebhookMode;
import com.nabd.comms.testkit.TestClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** COM-021 and the COM-010 status ground truth against a real Postgres (V6 migration, RLS) and the Valkey stand-in. */
class Com021QualityDbTest {

    static final String DB = "comms_quality";
    static final ObjectMapper JSON = new ObjectMapper();
    static JdbcTemplate app, su;
    static TransactionTemplate tx;
    static StringRedisTemplate valkey;
    static final ProviderStub stub = new ProviderStub();
    TestClock clock;
    SenderQualityService service;
    QualityJobs jobs;
    ThrottleGate gate;

    @BeforeAll
    static void start() {
        Infra.freshComms(DB);
        su = Infra.superuser(DB);
        var ds = Infra.appDataSource(DB);
        app = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var cf = new LettuceConnectionFactory("127.0.0.1", Infra.redisPort());
        cf.afterPropertiesSet();
        valkey = new StringRedisTemplate(cf);
        valkey.afterPropertiesSet();
    }

    @org.junit.jupiter.api.BeforeEach
    void perTest() {
        clock = new TestClock(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        service = new SenderQualityService(app, tx, new QualityEvents(valkey, JSON, clock, "in"), clock);
        var bf = new StaticListableBeanFactory();
        bf.addBean("provider", stub);
        jobs = new QualityJobs(app, tx, service, bf.getBeanProvider(Provider.class), clock);
        gate = new ThrottleGate(app, tx, clock);
        stub.phone = new Provider.PhoneStatus("GREEN", "TIER_1K");
    }

    UUID sender(UUID tenant) {
        UUID id = UUID.randomUUID();
        su.update("INSERT INTO sender_accounts (id, tenant_id, kind, provider, phone_number_id, access_token_ref, quality_rating, messaging_limit_tier, status) "
                + "VALUES (?,?,?,'meta_cloud',?,'ref','GREEN','TIER_1K','active')", id, tenant, tenant == null ? "SHARED" : "TENANT", "pn-" + id);
        return id;
    }

    String pn(UUID sender) {
        return "pn-" + sender;
    }

    String mode(UUID s) {
        return su.queryForObject("SELECT throttle_mode FROM sender_accounts WHERE id = ?", String.class, s);
    }

    List<Map<String, Object>> history(UUID s) {
        return su.queryForList("SELECT * FROM sender_quality_events WHERE sender_account_id = ? ORDER BY id", s);
    }

    /** CloudEvents published for this sender on ev:in:comms. */
    List<JsonNode> published(UUID s) {
        List<JsonNode> out = new ArrayList<>();
        for (var r : valkey.opsForStream().range("ev:in:comms", Range.unbounded())) {
            JsonNode ce = JSON.readTree(String.valueOf(r.getValue().get("ce")));
            if (ce.path("subject").asString().equals(s.toString())) out.add(ce);
        }
        return out;
    }

    // ---- acceptance 1 and 3: webhook, pause within a minute, alert ----

    @Test
    void ratingDropToYellowPausesBulkAtOnceKeepsRemindersAndAlerts() throws Exception {
        UUID s = sender(null);
        assertThat(gate.apply(s)).contains(Lane.BULK);
        stub.phone = new Provider.PhoneStatus("YELLOW", "TIER_1K");
        jobs.onQualityWebhook(pn(s));
        assertThat(mode(s)).isEqualTo("bulk_paused");
        clock.advance(Duration.ofSeconds(11)); // gate cache is 10 s: effective well inside 1 min
        assertThat(gate.apply(s)).contains(Lane.OTP, Lane.TRANSACTIONAL, Lane.REMINDER).doesNotContain(Lane.BULK);
        var h = history(s);
        assertThat(h).hasSize(1);
        assertThat(h.get(0)).containsEntry("kind", "rating").containsEntry("from_value", "GREEN").containsEntry("to_value", "YELLOW");
        assertThat(h.get(0).get("published_at")).as("alert published").isNotNull();
        var ev = published(s);
        assertThat(ev).hasSize(1);
        JsonNode ce = ev.get(0);
        assertThat(ce.path("type").asString()).isEqualTo("comms.sender.throttled.v1");
        assertThat(ce.path("id").asString()).isEqualTo(String.valueOf(h.get(0).get("id")));
        assertThat(ce.path("tenantid").asString()).as("shared sender: nil tenant").isEqualTo("00000000-0000-0000-0000-000000000000");
        assertThat(ce.path("data").path("from").asString()).isEqualTo("GREEN");
        assertThat(ce.path("data").path("to").asString()).isEqualTo("YELLOW");
        assertThat(ce.path("data").path("throttleMode").asString()).isEqualTo("bulk_paused");
        // the data keys are exactly what the catalogued schema allows and requires
        JsonNode schema = JSON.readTree(Files.readString(Path.of("../../api/events/comms.sender.throttled.v1.schema.json")));
        Set<String> keys = new java.util.TreeSet<>();
        ce.path("data").propertyNames().forEach(keys::add);
        Set<String> allowed = new java.util.TreeSet<>(), required = new java.util.TreeSet<>();
        schema.path("properties").propertyNames().forEach(allowed::add);
        schema.path("required").forEach(n -> required.add(n.asString()));
        assertThat(allowed).containsAll(keys);
        assertThat(keys).containsAll(required);
    }

    @Test
    void redAllowsOnlyOtpAndTransactionalAndATierChangeIsRecorded() {
        UUID s = sender(null);
        service.observe(s, "RED", "TIER_10K");
        assertThat(mode(s)).isEqualTo("replies_only");
        clock.advance(Duration.ofSeconds(11));
        assertThat(gate.apply(s)).containsExactlyInAnyOrder(Lane.OTP, Lane.TRANSACTIONAL);
        assertThat(history(s)).extracting(r -> r.get("kind")).containsExactly("rating", "tier");
    }

    @Test
    void anUnchangedRatingWritesNothingAndAlertsNobody() {
        UUID s = sender(null);
        service.observe(s, "GREEN", "TIER_1K");
        service.observe(s, "GREEN", "TIER_1K");
        assertThat(history(s)).isEmpty();
        assertThat(published(s)).isEmpty();
    }

    // ---- acceptance 2: block rate ----

    void messages(UUID tenant, UUID sender, int ok, int blocked) {
        for (int i = 0; i < ok + blocked; i++) {
            su.update("INSERT INTO outbound_messages (id, tenant_id, sender_account_id, to_address_enc, to_hash, template_name, language, status, error_code) "
                    + "VALUES (?,?,?,'\\x00','\\x00','tpl','en',?,?)", UUID.randomUUID(), tenant, sender, i < ok ? "delivered" : "failed", i < ok ? null : "131049");
        }
    }

    @Test
    void blockRateAbove1Point5PercentPausesBulkWhileStillGreenAndSaysWhy() {
        UUID t = UUID.randomUUID(), s = sender(null);
        messages(t, s, 196, 4); // 2.0 %
        jobs.blockRate(new QualityJobs.Sender(s, pn(s)));
        assertThat(mode(s)).isEqualTo("bulk_paused");
        var h = history(s);
        assertThat(h).hasSize(1);
        assertThat(h.get(0)).containsEntry("kind", "block_rate").containsEntry("reason", "block rate 2.00% over 24 h");
        assertThat(su.queryForObject("SELECT quality_rating FROM sender_accounts WHERE id = ?", String.class, s)).as("still GREEN").isEqualTo("GREEN");
        assertThat(published(s)).hasSize(1);
        assertThat(published(s).get(0).path("data").path("kind").asString()).isEqualTo("block_rate");
    }

    @Test
    void aTinySampleAndARateUnderTheLimitDoNotPause() {
        UUID t = UUID.randomUUID(), few = sender(null), fine = sender(null);
        messages(t, few, 8, 2); // 20 % of 10 sends: too few to say anything
        messages(t, fine, 297, 3); // 1.0 %
        jobs.blockRate(new QualityJobs.Sender(few, pn(few)));
        jobs.blockRate(new QualityJobs.Sender(fine, pn(fine)));
        assertThat(mode(few)).isEqualTo("auto");
        assertThat(mode(fine)).isEqualTo("auto");
    }

    // ---- acceptance 4: auto resume after 48 h GREEN ----

    @Test
    void bulkResumesOnlyAfter48HoursOfGreenAndTheHistoryRecordsIt() {
        UUID s = sender(null);
        service.observe(s, "YELLOW", null);
        clock.advance(Duration.ofHours(1));
        service.observe(s, "GREEN", null); // the GREEN run starts here
        assertThat(mode(s)).isEqualTo("bulk_paused");
        clock.advance(Duration.ofHours(47).plusMinutes(59));
        service.reevaluate(s);
        assertThat(mode(s)).as("47 h 59 min of GREEN").isEqualTo("bulk_paused");
        clock.advance(Duration.ofMinutes(1));
        service.reevaluate(s);
        assertThat(mode(s)).isEqualTo("auto");
        var h = history(s);
        assertThat(h.get(h.size() - 1)).containsEntry("reason", "recovered after 48 h GREEN").containsEntry("throttle_mode", "auto");
        assertThat(published(s)).hasSize(h.size());
    }

    @Test
    void aDipBackToYellowRestartsTheGreenClock() {
        UUID s = sender(null);
        service.observe(s, "YELLOW", null);
        service.observe(s, "GREEN", null);
        clock.advance(Duration.ofHours(40));
        service.observe(s, "YELLOW", null);
        service.observe(s, "GREEN", null);
        clock.advance(Duration.ofHours(40));
        service.reevaluate(s);
        assertThat(mode(s)).isEqualTo("bulk_paused");
    }

    // ---- acceptance 5: override ----

    @Test
    void overrideNeedsAReasonLastsAtMost72HoursAndExpires() {
        UUID s = sender(null);
        assertThatThrownBy(() -> service.override(s, Mode.replies_only, "  ", null)).isInstanceOf(SenderQualityService.OverrideRejected.class).hasMessage("REASON_REQUIRED");
        assertThatThrownBy(() -> service.override(s, Mode.replies_only, "x", clock.instant().plus(Duration.ofHours(73))))
                .hasMessage("EXPIRY_TOO_LONG");
        assertThatThrownBy(() -> service.override(s, Mode.replies_only, "x", clock.instant().minusSeconds(1))).hasMessage("EXPIRY_INVALID");
        service.override(s, Mode.replies_only, "Meta review ticket pending", null);
        assertThat(mode(s)).isEqualTo("replies_only");
        assertThat(su.queryForObject("SELECT throttle_expires_at FROM sender_accounts WHERE id = ?", java.sql.Timestamp.class, s).toInstant())
                .as("default expiry = 72 h").isBetween(clock.instant().plus(Duration.ofHours(72)).minusSeconds(1), clock.instant().plus(Duration.ofHours(72)).plusSeconds(1));
        service.observe(s, "GREEN", null);
        assertThat(mode(s)).as("an override beats the automatic state").isEqualTo("replies_only");
        clock.advance(Duration.ofHours(72).plusSeconds(1));
        service.reevaluate(s);
        assertThat(mode(s)).as("expired override: the automatic state returns").isEqualTo("auto");
        assertThat(su.queryForObject("SELECT throttle_reason FROM sender_accounts WHERE id = ?", String.class, s)).isNull();
        assertThat(history(s)).extracting(r -> r.get("kind")).contains("override");
    }

    @Test
    void anAutoOverrideEndsTheOverrideEarly() {
        UUID s = sender(null);
        service.override(s, Mode.bulk_paused, "campaign review", Instant.now().plus(Duration.ofHours(10)));
        service.override(s, Mode.auto, "review done", null);
        assertThat(mode(s)).isEqualTo("auto");
        assertThat(su.queryForObject("SELECT throttle_expires_at FROM sender_accounts WHERE id = ?", Object.class, s)).isNull();
    }

    // ---- tenancy ----

    @Test
    void anotherTenantNeverSeesAClinicsSenderHistoryAndNoTenantCanWriteTheSharedSender() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        UUID own = sender(a), shared = sender(null);
        service.observe(own, "YELLOW", null);
        service.observe(shared, "YELLOW", null);
        int asA = tx.execute(st -> { bind(a); return app.queryForObject("SELECT count(*) FROM sender_quality_events WHERE sender_account_id = ?", Integer.class, own); });
        int asB = tx.execute(st -> { bind(b); return app.queryForObject("SELECT count(*) FROM sender_quality_events WHERE sender_account_id = ?", Integer.class, own); });
        int sharedAsB = tx.execute(st -> { bind(b); return app.queryForObject("SELECT count(*) FROM sender_quality_events WHERE sender_account_id = ?", Integer.class, shared); });
        assertThat(asA).isEqualTo(1);
        assertThat(asB).as("clinic B cannot read clinic A's history").isZero();
        assertThat(sharedAsB).as("the shared sender's history is readable by all").isEqualTo(1);
        int updated = tx.execute(st -> { bind(b); return app.update("UPDATE sender_accounts SET throttle_mode = 'auto' WHERE id = ?", shared); });
        assertThat(updated).as("a tenant cannot unthrottle the shared sender").isZero();
    }

    static void bind(UUID tenant) {
        app.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
    }

    // ---- COM-010 acceptance 3: duplicated and reordered webhooks vs ground truth, dead letters visible ----

    @Test
    void duplicatedAndReorderedStatusWebhooksEndAtTheStubsGroundTruth() {
        for (WebhookMode wm : WebhookMode.values()) {
            UUID t = UUID.randomUUID(), s = sender(null);
            var stub = new ProviderStub();
            var clk = new TestClock(Instant.now());
            var sch = new LaneScheduler();
            var store = new JdbcOutcomeStore(app);
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                UUID id = UUID.randomUUID();
                ids.add(id);
                su.update("INSERT INTO outbound_messages (id, tenant_id, sender_account_id, to_address_enc, to_hash, template_name, language, priority) VALUES (?,?,?,'\\x00','\\x00','tpl','en','reminder')", id, t, s);
                sch.add(new LaneScheduler.Item(id, t, Lane.REMINDER, clk.instant(), clk.instant(), 0));
            }
            UUID bad = UUID.randomUUID();
            su.update("INSERT INTO outbound_messages (id, tenant_id, sender_account_id, to_address_enc, to_hash, template_name, language, priority) VALUES (?,?,?,'\\x00','\\x00','tpl','en','reminder')", bad, t, s);
            sch.add(new LaneScheduler.Item(bad, t, Lane.REMINDER, clk.instant(), clk.instant(), 0));
            SenderLoop.Outcomes inTenant = new SenderLoop.Outcomes() {
                public void sent(LaneScheduler.Item it, String w, Instant at) { tx.executeWithoutResult(st -> { bind(t); store.sent(it, w, at); }); }
                public void deadLettered(LaneScheduler.Item it, String c, Instant at) { tx.executeWithoutResult(st -> { bind(t); store.deadLettered(it, c, at); }); }
            };
            var loop = new SenderLoop(s, it -> new Provider.OutboundRequest(it.messageId(), "pn", it.messageId().equals(bad) ? "+bad" : "+1555", "tpl"),
                    80, 5, sch, new TokenBucket(valkey), stub, inTenant, id -> Set.of(Lane.values()), clk, new SimpleMeterRegistry());
            stub.mode = ProviderStub.Mode.STORM_429;
            for (int i = 0; i < 3; i++) { loop.tick(); clk.advance(Duration.ofSeconds(1)); }
            stub.mode = ProviderStub.Mode.NORMAL;
            for (int i = 0; i < 200 && sch.depth() > 0; i++) { loop.tick(); clk.advance(Duration.ofSeconds(1)); }
            assertThat(stub.acceptedMessages()).as(wm.name()).isEqualTo(100);
            assertThat(stub.duplicates()).isZero();
            assertThat(su.queryForObject("SELECT count(*) FROM outbound_messages WHERE tenant_id = ? AND status = 'sent'", Integer.class, t)).isEqualTo(100);
            assertThat(su.queryForMap("SELECT status, error_code FROM outbound_messages WHERE id = ?", bad)).as("dead letter visible")
                    .containsEntry("status", "failed").containsEntry("error_code", "131026");

            var statusApplier = new StatusApplier(app);
            for (var e : stub.statusWebhooks(wm)) {
                tx.executeWithoutResult(st -> { bind(t); statusApplier.apply(e.wamid(), e.status()); });
            }
            // ground truth: each message's final status, plus the provider ids the gateway stored
            var truth = stub.truthByWamid();
            var rows = su.queryForList("SELECT provider_message_id, status FROM outbound_messages WHERE tenant_id = ? AND provider_message_id IS NOT NULL", t);
            assertThat(rows).as(wm.name()).hasSize(100);
            for (var r : rows) {
                assertThat(r.get("status")).as(wm + " " + r.get("provider_message_id")).isEqualTo(truth.get((String) r.get("provider_message_id")));
            }
        }
    }
}
