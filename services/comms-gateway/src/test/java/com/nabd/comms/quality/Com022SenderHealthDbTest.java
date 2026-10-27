package com.nabd.comms.quality;

import com.nabd.comms.testkit.Infra;
import com.nabd.comms.testkit.TestClock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** COM-022 against a real Postgres (V1..V14 migrations, RLS): owner health, tenant isolation, platform fleet. */
class Com022SenderHealthDbTest {

    static final String DB = "comms_sender_health";
    static JdbcTemplate app, su;
    static TransactionTemplate tx;
    TestClock clock = new TestClock(Instant.now());
    SenderHealthService svc;

    @BeforeAll
    static void start() {
        Infra.freshComms(DB);
        su = Infra.superuser(DB);
        var ds = Infra.appDataSource(DB);
        app = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    SenderHealthService svc() {
        return new SenderHealthService(app, tx, clock);
    }

    UUID sender(UUID tenant, String rating, String tier, String mode, String region) {
        UUID id = UUID.randomUUID();
        su.update("INSERT INTO sender_accounts (id, tenant_id, kind, provider, phone_number_id, display_phone, access_token_ref, quality_rating, messaging_limit_tier, status, throttle_mode, region) "
                + "VALUES (?,?,?,'meta_cloud',?,?,'ref',?,?,'active',?,?)", id, tenant, tenant == null ? "SHARED" : "TENANT", "pn-" + id, "+9140000" + (int) (Math.random() * 90000 + 10000), rating, tier, mode, region);
        return id;
    }

    void message(UUID tenant, UUID sender, String status, String error, Instant at) {
        su.update("INSERT INTO outbound_messages (id, tenant_id, sender_account_id, to_address_enc, to_hash, template_name, language, status, error_code, updated_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?)", UUID.randomUUID(), tenant, sender, new byte[]{1}, new byte[]{2}, "t", "en", status, error, Timestamp.from(at));
    }

    void template(UUID tenant, String name, String status) {
        su.update("INSERT INTO whatsapp_templates (id, tenant_id, name, meta_name, language, category, header_text, body, param_count, status) VALUES (?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), tenant, name, "m_" + UUID.randomUUID().toString().replace("-", ""), "en", "UTILITY", "h", "b", 0, status);
    }

    void event(UUID sender, UUID tenant, String kind, String from, String to, String mode, Instant at) {
        su.update("INSERT INTO sender_quality_events (sender_account_id, tenant_id, kind, from_value, to_value, throttle_mode, at) VALUES (?,?,?,?,?,?,?)",
                sender, tenant, kind, from, to, mode, Timestamp.from(at));
    }

    // AC1 + AC2
    @Test
    void ownerSeesStatusQualityTierBlockRateChartThrottleTemplatesAndWhatHappenedInUnderASecond() {
        UUID t = UUID.randomUUID();
        UUID s = sender(t, "YELLOW", "TIER_1K", "bulk_paused", "in");
        Instant now = clock.instant();
        for (int i = 0; i < 100; i++) message(t, s, i < 3 ? "failed" : "delivered", i < 3 ? "131049" : null, now.minus(Duration.ofHours(30))); // ~yesterday: 3 % blocked
        for (int i = 0; i < 10; i++) message(t, s, "delivered", null, now.minus(Duration.ofHours(1)));                                          // today: none
        message(t, s, "queued", null, now.minus(Duration.ofHours(2)));                                                                      // not sent yet: not in the rate
        message(t, s, "failed", "131049", now.minus(Duration.ofDays(9)));                                                                 // older than 7 days
        template(t, "reminder", "approved");
        template(t, "recall", "rejected");
        event(s, t, "rating", "GREEN", "YELLOW", "bulk_paused", now.minus(Duration.ofHours(26)));
        event(s, t, "tier", "TIER_250", "TIER_1K", "bulk_paused", now.minus(Duration.ofDays(40))); // outside 30 days

        long t0 = System.nanoTime();
        var h = svc().health(t);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).as("page data in < 1 s").isLessThan(Duration.ofSeconds(1));

        assertThat(h.senders()).hasSize(1);
        var x = h.senders().get(0);
        assertThat(x.status()).isEqualTo("active");
        assertThat(x.rating()).isEqualTo("YELLOW");
        assertThat(x.quality()).isEqualTo("Medium");
        assertThat(x.tierLabel()).isEqualTo("1,000 patients a day");
        assertThat(x.throttleMode()).isEqualTo("bulk_paused");
        assertThat(x.throttleLine()).isEqualTo(0.015);
        assertThat(x.blockRate7d()).hasSize(7);
        assertThat(x.blockRate7d().stream().mapToDouble(Double::doubleValue).max().orElse(0)).isEqualTo(0.03);
        assertThat(x.blockRate7d().get(6)).as("today, oldest first").isEqualTo(0.0);
        assertThat(h.templates()).extracting(SenderHealthService.Template::status).containsExactlyInAnyOrder("approved", "rejected");
        assertThat(x.events()).hasSize(1);
        assertThat(x.events().get(0).text()).contains("from High to Medium").contains("paused").doesNotContain("131049");
        assertThat(x.events().get(0).at()).isBefore(now.minus(Duration.ofHours(25)));
    }

    // tenant isolation + the shared number fallback
    @Test
    void aClinicNeverSeesAnotherClinicsNumberMessagesOrTemplates() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        UUID sa = sender(a, "GREEN", "TIER_250", "auto", "in");
        message(a, sa, "failed", "131049", clock.instant().minus(Duration.ofHours(3)));
        template(a, "only_a", "approved");
        event(sa, a, "rating", "YELLOW", "GREEN", "auto", clock.instant().minus(Duration.ofHours(5)));
        UUID shared = sender(null, "GREEN", "TIER_1K", "auto", "in");
        message(b, shared, "delivered", null, clock.instant().minus(Duration.ofHours(1)));

        var hb = svc().health(b);
        assertThat(hb.senders()).extracting(SenderHealthService.Sender::id).contains(shared).doesNotContain(sa);
        assertThat(hb.senders()).extracting(SenderHealthService.Sender::kind).containsOnly("SHARED");
        assertThat(hb.templates()).isEmpty();
        var sharedView = hb.senders().stream().filter(x -> x.id().equals(shared)).findFirst().orElseThrow();
        assertThat(sharedView.blockRate7d().stream().mapToDouble(Double::doubleValue).sum()).as("A's block is not in B's chart").isZero();
        var ha = svc().health(a);
        assertThat(ha.senders()).extracting(SenderHealthService.Sender::id).containsExactly(sa);
        assertThat(ha.templates()).extracting(SenderHealthService.Template::name).containsExactly("only_a");
        assertThat(svc().qualityLabel(a)).isEqualTo("High");
        assertThat(svc().health(UUID.randomUUID()).senders()).extracting(SenderHealthService.Sender::id).contains(shared); // a clinic with no number sends from the shared one
    }

    // AC3
    @Test
    void fleetListsEverySenderWithRatingTierThrottleAndDeliveredRateAndFiltersByRating() {
        String region = "r" + UUID.randomUUID().toString().substring(0, 6);
        UUID t1 = UUID.randomUUID(), t2 = UUID.randomUUID();
        UUID g = sender(t1, "GREEN", "TIER_1K", "auto", region), y = sender(t2, "YELLOW", "TIER_250", "bulk_paused", region), none = sender(null, "RED", "TIER_250", "replies_only", region);
        Instant now = clock.instant();
        for (int i = 0; i < 10; i++) message(t1, g, i < 8 ? "delivered" : "failed", i < 8 ? null : "131026", now.minus(Duration.ofDays(1)));
        message(t1, g, "delivered", null, now.minus(Duration.ofDays(8))); // outside 7 days
        message(t2, y, "read", null, now.minus(Duration.ofHours(2)));

        var all = svc().fleet(null, region, null, 50);
        assertThat(all.items()).hasSize(3);
        var gi = all.items().stream().filter(i -> i.senderAccountId().equals(g)).findFirst().orElseThrow();
        assertThat(gi.deliveredRate7d()).isEqualTo(0.8);
        assertThat(gi.sends7d()).isEqualTo(10);
        assertThat(gi.tier()).isEqualTo("TIER_1K");
        assertThat(gi.throttleMode()).isEqualTo("auto");
        assertThat(gi.tenantId()).isEqualTo(t1);
        assertThat(all.items().stream().filter(i -> i.senderAccountId().equals(y)).findFirst().orElseThrow().deliveredRate7d()).isEqualTo(1.0);
        assertThat(all.items().stream().filter(i -> i.senderAccountId().equals(none)).findFirst().orElseThrow().deliveredRate7d()).as("no sends = no rate").isNull();

        assertThat(svc().fleet("yellow", region, null, 50).items()).extracting(SenderHealthService.FleetItem::senderAccountId).containsExactly(y);
        assertThat(svc().fleet("RED", region, null, 50).items()).extracting(SenderHealthService.FleetItem::senderAccountId).containsExactly(none);
        assertThat(svc().fleet(null, "other-region", null, 50).items()).isEmpty();

        var p1 = svc().fleet(null, region, null, 2);
        assertThat(p1.items()).hasSize(2);
        assertThat(p1.nextCursor()).isNotNull();
        var p2 = svc().fleet(null, region, p1.nextCursor(), 2);
        assertThat(p2.items()).hasSize(1);
        assertThat(p2.nextCursor()).isNull();
    }

    @Test
    void plainWordTextsAreStable() {
        assertThat(SenderHealthService.describe("rating", "YELLOW", "RED", "replies_only")).contains("from Medium to Low").contains("Only replies");
        assertThat(SenderHealthService.describe("rating", "YELLOW", "GREEN", "auto")).contains("Sending is normal");
        assertThat(SenderHealthService.describe("block_rate", null, "0.02", "bulk_paused")).contains("1.5%");
        assertThat(SenderHealthService.describe("override", "auto", "bulk_paused", "bulk_paused")).startsWith("Nabd support");
        assertThat(SenderHealthService.qualityWord(null)).isEqualTo("Unknown");
        assertThat(SenderHealthService.tierLabel("TIER_UNLIMITED")).isEqualTo("Unlimited");
    }

    @Test
    void theInternalApiNeedsTheServiceTokenAndRejectsABadFilter() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SenderAdminController(svc(), "test-token")).build();
        UUID t = UUID.randomUUID();
        mvc.perform(get("/internal/v1/senders/health").header("X-Tenant-Id", t)).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/senders").header("X-Internal-Token", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/senders/health").header("X-Internal-Token", "test-token")).andExpect(status().is4xxClientError());
        mvc.perform(get("/internal/v1/senders?rating=PURPLE").header("X-Internal-Token", "test-token")).andExpect(status().isBadRequest());
        mvc.perform(get("/internal/v1/senders?cursor=not-a-uuid").header("X-Internal-Token", "test-token")).andExpect(status().isBadRequest());
        mvc.perform(get("/internal/v1/senders/health").header("X-Tenant-Id", t).header("X-Internal-Token", "test-token")).andExpect(status().isOk()).andExpect(jsonPath("$.senders").isArray());
        MockMvc closed = MockMvcBuilders.standaloneSetup(new SenderAdminController(svc(), "")).build();
        closed.perform(get("/internal/v1/senders").header("X-Internal-Token", "")).andExpect(status().isUnauthorized());
    }
}
