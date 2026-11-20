package com.nabd.comms.usage;

import com.nabd.comms.consumer.CommsConsumerProperties;
import com.nabd.comms.events.StreamEventPublisher;
import com.nabd.comms.testkit.Infra;
import com.nabd.comms.testkit.TestClock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** COM-015 against a real Postgres (RLS as comms_app) and a Valkey stand-in: pricing capture, the daily rollup and the usage event. */
class Com015UsageTest {

    static final ObjectMapper JSON = JsonMapper.builder().build();
    static final String STREAM_REGION = "usg" + Long.toString(System.nanoTime(), 36);

    static JdbcTemplate su;
    static JdbcTemplate app;
    static TransactionTemplate tx;
    static StringRedisTemplate valkey;

    @BeforeAll
    static void start() {
        String db = "usage_" + UUID.randomUUID().toString().replace("-", "");
        Infra.freshComms(db);
        var ds = Infra.appDataSource(db);
        app = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        su = Infra.superuser(db);
        var cf = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", Infra.redisPort()));
        cf.afterPropertiesSet();
        valkey = new StringRedisTemplate(cf);
    }

    static UsageService service(TestClock clock, String region) {
        var props = new CommsConsumerProperties(STREAM_REGION, new CommsConsumerProperties.Consumer(true, 50, 5, 60000));
        return new UsageService(app, tx, new StreamEventPublisher(valkey, props, JSON, clock), clock, region, 3);
    }

    /** A sent message as the sender leaves it; the recipient columns hold opaque bytes (never read here). */
    static String message(UUID tenant, String channel) {
        String providerId = "wamid." + UUID.randomUUID();
        su.update("INSERT INTO outbound_messages (id, tenant_id, channel, to_address_enc, to_hash, template_name, language, status, provider_message_id) "
                + "VALUES (?,?,?,?,?, 'appt_reminder_24h', 'en', 'sent', ?)", UUID.randomUUID(), tenant, channel, new byte[] {1}, new byte[] {2}, providerId);
        return providerId;
    }

    /** Meta's status webhook item, as the tenant-bound webhook processor hands it over. */
    static boolean metaStatus(UsageService svc, UUID tenant, String providerId, String category, boolean billable) {
        JsonNode status = JSON.readTree("{\"id\":\"" + providerId + "\",\"status\":\"delivered\",\"timestamp\":\"1763269200\",\"recipient_id\":\"0\","
                + "\"pricing\":{\"billable\":" + billable + ",\"pricing_model\":\"PMP\",\"category\":\"" + category + "\"}}");
        return tx.execute(s -> {
            app.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
            return svc.priceFromMetaStatus(status);
        });
    }

    static List<JsonNode> usageEvents(UUID tenant) {
        List<JsonNode> out = new ArrayList<>();
        var records = valkey.opsForStream().range("ev:" + STREAM_REGION + ":comms", Range.unbounded());
        if (records != null) {
            records.stream().map(r -> JSON.readTree(String.valueOf(r.getValue().get("ce"))))
                    .filter(ce -> ce.path("type").asString().equals(UsageService.EVENT) && ce.path("tenantid").asString().equals(tenant.toString())).forEach(out::add);
        }
        return out;
    }

    static UsageService.Item item(UsageService.Month m, String channel, String category) {
        return m.channels().stream().filter(i -> i.channel().equals(channel) && i.category().equals(category)).findFirst().orElseThrow();
    }

    @Test
    void pricingFromStatusWebhooksIsRolledUpByChannelAndCategoryAndTheMonthIsRoundedOnce() {
        TestClock clock = new TestClock(Instant.parse("2026-11-16T05:00:00Z"));
        UsageService svc = service(clock, "in");
        UUID tenant = UUID.randomUUID();

        // day 1: three utility (3 x 11.5 paise = 34.5), one marketing (78.46 paise), one free service reply
        List<String> day1 = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            day1.add(message(tenant, "whatsapp"));
            assertThat(metaStatus(svc, tenant, day1.get(i), "utility", true)).isTrue();
        }
        assertThat(metaStatus(svc, tenant, message(tenant, "whatsapp"), "MARKETING", true)).isTrue();
        assertThat(metaStatus(svc, tenant, message(tenant, "whatsapp"), "service", false)).isTrue();
        assertThat(metaStatus(svc, tenant, day1.get(0), "utility", true)).as("the 'read' status repeats the pricing object: priced once").isFalse();
        assertThat(metaStatus(svc, tenant, "wamid.unknown", "utility", true)).isFalse();
        assertThat(su.queryForMap("SELECT pricing_category, currency, cost_micros, cost_minor FROM outbound_messages WHERE provider_message_id = ?", day1.get(0)))
                .containsEntry("pricing_category", "utility").containsEntry("currency", "INR").containsEntry("cost_micros", 115000L).containsEntry("cost_minor", 12L);

        assertThat(svc.rollup()).as("the day is still open: nothing reported").isZero();
        assertThat(usageEvents(tenant)).isEmpty();
        UsageService.Month open = svc.month(tenant, YearMonth.of(2026, 11));
        assertThat(item(open, "whatsapp", "utility")).isEqualTo(new UsageService.Item("whatsapp", "utility", 3, 35, "INR"));

        // day 2: three more utility messages
        clock.advance(Duration.ofDays(1));
        for (int i = 0; i < 3; i++) {
            assertThat(metaStatus(svc, tenant, message(tenant, "whatsapp"), "utility", true)).isTrue();
        }
        assertThat(svc.rollup()).as("day 1 closed").isEqualTo(1);
        assertThat(svc.rollup()).as("idempotent: the same rows, no second event").isZero();

        UsageService.Month m = svc.month(tenant, YearMonth.of(2026, 11));
        // 6 x 11.5 = 69 paise exactly; summing the two rounded days (35 + 35) would be 1.4% over the provider's invoice
        assertThat(item(m, "whatsapp", "utility")).isEqualTo(new UsageService.Item("whatsapp", "utility", 6, 69, "INR"));
        assertThat(item(m, "whatsapp", "marketing")).isEqualTo(new UsageService.Item("whatsapp", "marketing", 1, 78, "INR"));
        assertThat(item(m, "whatsapp", "service")).isEqualTo(new UsageService.Item("whatsapp", "service", 1, 0, "INR"));
        assertThat(m.whatsappCount()).isEqualTo(8);
        assertThat(svc.month(tenant, YearMonth.of(2026, 10)).channels()).isEmpty();
        assertThat(su.queryForObject("SELECT count(*) FROM message_usage_daily WHERE tenant_id = ?", Integer.class, tenant)).isEqualTo(4);

        List<JsonNode> events = usageEvents(tenant);
        assertThat(events).hasSize(1);
        JsonNode ce = events.get(0), data = ce.path("data");
        assertThat(ce.path("id").asString()).isEqualTo("usage:" + tenant + ":2026-11-16");
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("tenantId", "day", "items", "whatsappMonthCount", "version"); // counts and cost only
        assertThat(data.path("day").asString()).isEqualTo("2026-11-16");
        assertThat(data.path("whatsappMonthCount").asLong()).isEqualTo(5);
        assertThat(data.path("items").size()).isEqualTo(3);
        JsonNode utility = data.path("items").get(2);
        assertThat(utility.propertyNames()).containsExactlyInAnyOrder("channel", "category", "count", "costMinor", "currency");
        assertThat(utility.path("category").asString()).isEqualTo("utility");
        assertThat(utility.path("count").asInt()).isEqualTo(3);
        assertThat(utility.path("costMinor").asLong()).isEqualTo(35);

        // day 3: day 2 closes with the month-to-date count
        clock.advance(Duration.ofDays(1));
        assertThat(svc.rollup()).isEqualTo(1);
        assertThat(usageEvents(tenant)).hasSize(2);
        assertThat(usageEvents(tenant).get(1).path("data").path("whatsappMonthCount").asLong()).isEqualTo(8);
    }

    @Test
    void aClinicSeesOnlyItsOwnUsageAndItsDayIsItsOwnTimeZone() {
        TestClock clock = new TestClock(Instant.parse("2026-12-10T20:00:00Z")); // 01:30 on the 11th in Kolkata
        UsageService svc = service(clock, "in");
        UUID kolkata = UUID.randomUUID(), other = UUID.randomUUID();
        su.update("INSERT INTO tenant_messaging_settings (tenant_id, time_zone, quiet_enabled, quiet_start, quiet_end, prayer_enabled, prayer_method, prayer_buffer_min, version) "
                + "VALUES (?, 'Asia/Kolkata', false, '21:00', '08:00', false, 'mwl', 0, 1)", kolkata);
        assertThat(metaStatus(svc, kolkata, message(kolkata, "whatsapp"), "utility", true)).isTrue();
        assertThat(metaStatus(svc, other, message(other, "whatsapp"), "marketing", true)).isTrue();
        // a status for another clinic's message, processed under the wrong tenant, prices nothing (RLS)
        String foreign = message(other, "whatsapp");
        assertThat(metaStatus(svc, kolkata, foreign, "utility", true)).isFalse();

        svc.rollup();
        assertThat(su.queryForObject("SELECT day::text FROM message_usage_daily WHERE tenant_id = ?", String.class, kolkata)).isEqualTo("2026-12-11");
        assertThat(su.queryForObject("SELECT day::text FROM message_usage_daily WHERE tenant_id = ?", String.class, other)).isEqualTo("2026-12-10");
        assertThat(svc.month(kolkata, YearMonth.of(2026, 12)).channels()).extracting(UsageService.Item::category).containsExactly("utility");
        assertThat(svc.month(other, YearMonth.of(2026, 12)).channels()).extracting(UsageService.Item::category).containsExactly("marketing");
        Integer visible = tx.execute(s -> {
            app.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, kolkata.toString());
            return app.queryForObject("SELECT count(*) FROM message_usage_daily", Integer.class);
        });
        assertThat(visible).isEqualTo(1);
    }

    @Test
    void theProvidersOwnCostWinsAndARegionWithoutAPriceListPricesNothing() {
        TestClock clock = new TestClock(Instant.parse("2027-01-05T05:00:00Z"));
        UUID tenant = UUID.randomUUID();
        String sms = message(tenant, "sms");
        UUID id = su.queryForObject("SELECT id FROM outbound_messages WHERE provider_message_id = ?", UUID.class, sms);
        Boolean priced = tx.execute(s -> {
            app.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
            return UsageService.price(app, clock, "in", id, "sms", "otp", true, 180000L);
        });
        assertThat(priced).isTrue();
        assertThat(su.queryForMap("SELECT pricing_category, currency, cost_minor FROM outbound_messages WHERE id = ?", id))
                .containsEntry("pricing_category", "otp").containsEntry("currency", "INR").containsEntry("cost_minor", 18L);

        // a WhatsApp category Meta adds later is still counted, at cost 0, in the channel's currency
        UsageService svc = service(clock, "in");
        String wa = message(tenant, "whatsapp");
        assertThat(metaStatus(svc, tenant, wa, "authentication_international", true)).isTrue();
        assertThat(su.queryForObject("SELECT cost_micros FROM outbound_messages WHERE provider_message_id = ?", Long.class, wa)).isZero();
        // no price list for the region: unpriced, so never shown with a wrong currency
        assertThat(metaStatus(service(clock, "qa"), tenant, message(tenant, "whatsapp"), "utility", true)).isFalse();
        assertThat(UsageService.minor(784600, "INR")).isEqualTo(78);
        assertThat(UsageService.minor(345000, "INR")).isEqualTo(35);
    }
}
