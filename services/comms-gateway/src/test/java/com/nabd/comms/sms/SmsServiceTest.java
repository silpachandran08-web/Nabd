package com.nabd.comms.sms;

import com.nabd.comms.consumer.CommsConsumerProperties;
import com.nabd.comms.events.StreamEventPublisher;
import com.nabd.comms.gate.SendGate;
import com.nabd.comms.inbox.FieldCrypto;
import com.nabd.comms.inbox.InboxException;
import com.nabd.comms.store.ConsentSnapshotStore;
import com.nabd.comms.testkit.Infra;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** COM-026 against a real Postgres (RLS as comms_app), a Valkey stand-in and a BSP stub on a local HTTP port. */
class SmsServiceTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T05:00:00Z"), ZoneOffset.UTC);
    static final ObjectMapper JSON = JsonMapper.builder().build();
    static final String OTP_BODY = "{#var#} is your Nabd verification code. Valid for 10 minutes. Do not share it. - NABDHS";
    static final String REGION = "smst";

    static JdbcTemplate su;
    static JdbcTemplate app;
    static TransactionTemplate tx;
    static FieldCrypto crypto;
    static StringRedisTemplate valkey;
    static HttpServer bsp;
    static final List<JsonNode> bspRequests = new CopyOnWriteArrayList<>();
    static final AtomicInteger bspStatus = new AtomicInteger(200);
    static final AtomicInteger seq = new AtomicInteger();
    static String otpTemplateId;

    @BeforeAll
    static void start() throws Exception {
        String db = "sms_" + UUID.randomUUID().toString().replace("-", "");
        Infra.freshComms(db);
        var ds = Infra.appDataSource(db);
        app = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        su = Infra.superuser(db);
        var cf = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", Infra.redisPort()));
        cf.afterPropertiesSet();
        valkey = new StringRedisTemplate(cf);
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        crypto = new FieldCrypto(key);
        bsp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bsp.createContext("/sms", ex -> {
            bspRequests.add(JSON.readTree(ex.getRequestBody().readAllBytes()));
            int st = bspStatus.get();
            byte[] out = (st == 200 ? "{\"id\":\"bsp-" + seq.incrementAndGet() + "\"}" : "{\"error\":\"x\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(st, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        bsp.start();
        otpTemplateId = "1107" + System.nanoTime();
        su.update("INSERT INTO sms_templates (tenant_id, key, language, dlt_entity_id, dlt_template_id, header, category, body) VALUES (NULL, 'otp', 'en', ?, ?, 'NABDHS', 'otp', ?)",
                "1201000000000000001", otpTemplateId, OTP_BODY);
    }

    @AfterAll
    static void stop() {
        bsp.stop(0);
    }

    @BeforeEach
    void reset() {
        bspRequests.clear();
        bspStatus.set(200);
    }

    static SmsService service(String region) {
        var meters = new SimpleMeterRegistry();
        var props = new CommsConsumerProperties(REGION, new CommsConsumerProperties.Consumer(true, 50, 5, 60000));
        return new SmsService(app, tx, crypto, new BspSmsProvider("bsp", "http://127.0.0.1:" + bsp.getAddress().getPort(), "k", JSON),
                new SendGate(new ConsentSnapshotStore(app), app, meters, CLOCK), new StreamEventPublisher(valkey, props, JSON, CLOCK), JSON, meters, CLOCK, region, java.time.Duration.ofHours(24));
    }

    static String otpText(String code) {
        return OTP_BODY.replace("{#var#}", code);
    }

    static final String PHONE = "+919876543210";

    @Test
    void anOtpSendCarriesTheDltEntityHeaderAndTemplateIdAndTheRegisteredText() {
        UUID tenant = UUID.randomUUID();
        var sent = service("in").send(tenant, new SmsService.SendRequest(PHONE, otpText("482913"), null, null));

        assertThat(sent.status()).isEqualTo("sent");
        assertThat(bspRequests).hasSize(1);
        JsonNode req = bspRequests.get(0);
        assertThat(req.path("dltEntityId").asString()).isEqualTo("1201000000000000001");
        assertThat(req.path("dltTemplateId").asString()).isEqualTo(otpTemplateId);
        assertThat(req.path("sender").asString()).isEqualTo("NABDHS");
        assertThat(req.path("text").asString()).isEqualTo(otpText("482913"));
        assertThat(req.path("to").asString()).isEqualTo(PHONE);
        assertThat(req.path("reference").asString()).isEqualTo(sent.messageId().toString());

        var row = su.queryForMap("SELECT channel, status, provider_message_id, template_name, to_address_enc FROM outbound_messages WHERE id = ?", sent.messageId());
        assertThat(row.get("channel")).isEqualTo("sms");
        assertThat(su.queryForObject("SELECT catalogue_version FROM outbound_messages WHERE id = ?", String.class, sent.messageId()))
                .as("FEF-041: the catalogue artefact version is recorded per send").isEqualTo(com.nabd.comms.i18n.PatientCatalogue.get().version());
        assertThat(row.get("status")).isEqualTo("sent");
        assertThat((String) row.get("provider_message_id")).startsWith("bsp-");
        assertThat(row.get("template_name")).isEqualTo("otp");
        assertThat(new String((byte[]) row.get("to_address_enc"), StandardCharsets.ISO_8859_1)).doesNotContain("9876543210"); // encrypted at rest
    }

    @Test
    void textThatMatchesNoRegisteredTemplateIsRefusedLocallyAndNeverReachesTheBsp() {
        UUID tenant = UUID.randomUUID();
        assertThatThrownBy(() -> service("in").send(tenant, new SmsService.SendRequest(PHONE, otpText("482913") + " ", null, null)))
                .isInstanceOfSatisfying(InboxException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(422);
                    assertThat(e.code()).isEqualTo("DLT_TEMPLATE_MISMATCH");
                });
        assertThatThrownBy(() -> service("in").send(tenant, new SmsService.SendRequest(PHONE, "Your appointment is confirmed", null, null)))
                .hasMessage("DLT_TEMPLATE_MISMATCH");
        assertThat(bspRequests).isEmpty();
        assertThat(su.queryForObject("SELECT count(*) FROM outbound_messages WHERE tenant_id = ?", Integer.class, tenant)).isZero();
    }

    @Test
    void aKsaDeploymentRefusesEverySmsSend() {
        UUID tenant = UUID.randomUUID();
        for (String region : List.of("sa", "ksa", "ae")) {
            assertThatThrownBy(() -> service(region).send(tenant, new SmsService.SendRequest(PHONE, otpText("482913"), null, null)))
                    .isInstanceOfSatisfying(InboxException.class, e -> assertThat(e.code()).isEqualTo("SMS_DISABLED_FOR_REGION"));
        }
        assertThat(bspRequests).isEmpty();
        assertThat(su.queryForObject("SELECT count(*) FROM outbound_messages WHERE tenant_id = ?", Integer.class, tenant)).isZero();
    }

    @Test
    void aClinicsOwnRegistrationIsInvisibleToOtherTenantsAndPromotionalNeedsMarketingConsent() {
        UUID clinic = UUID.randomUUID(), other = UUID.randomUUID(), patient = UUID.randomUUID();
        su.update("INSERT INTO sms_templates (tenant_id, key, language, dlt_entity_id, dlt_template_id, header, category, body) VALUES (?, 'camp', 'en', 'e2', ?, 'CLINIC', 'promotional', ?)",
                clinic, "1107p" + System.nanoTime(), "Free dental camp on {#var#} at {#var#}. Reply STOP to opt out.");
        String text = "Free dental camp on 20 Nov at Main Road. Reply STOP to opt out.";
        assertThatThrownBy(() -> service("in").send(other, new SmsService.SendRequest(PHONE, text, null, null))).hasMessage("DLT_TEMPLATE_MISMATCH");

        var blocked = service("in").send(clinic, new SmsService.SendRequest(PHONE, text, patient, null));
        assertThat(blocked.status()).isEqualTo("blocked"); // no marketing consent in the snapshot
        assertThat(bspRequests).isEmpty();
        assertThat(su.queryForObject("SELECT error_code FROM outbound_messages WHERE id = ?", String.class, blocked.messageId())).isEqualTo("no-marketing-consent");
    }

    @Test
    void theSameNotificationRequestSendsOnceAndABspRejectionIsAVisibleFailure() {
        UUID tenant = UUID.randomUUID(), request = UUID.randomUUID();
        var first = service("in").send(tenant, new SmsService.SendRequest(PHONE, otpText("111111"), null, request));
        var again = service("in").send(tenant, new SmsService.SendRequest(PHONE, otpText("111111"), null, request));
        assertThat(again).isEqualTo(first);
        assertThat(bspRequests).hasSize(1);
        // S24 T1-BUG-7: the fallback caller is told the SMS is another call's, so the desk's second press is not audited as a send
        assertThatThrownBy(() -> service("in").send(tenant, new SmsService.SendRequest(PHONE, otpText("111111"), null, request), UUID.randomUUID()))
                .hasMessage("DUPLICATE_REQUEST");
        assertThat(bspRequests).hasSize(1);

        bspStatus.set(400);
        var rejected = service("in").send(tenant, new SmsService.SendRequest(PHONE, otpText("222222"), null, null));
        assertThat(rejected.status()).isEqualTo("failed");
        assertThat(su.queryForObject("SELECT error_code FROM outbound_messages WHERE id = ?", String.class, rejected.messageId())).isEqualTo("http_400");
        bspStatus.set(429);
        assertThat(service("in").send(tenant, new SmsService.SendRequest(PHONE, otpText("333333"), null, null)).status()).isEqualTo("failed");
    }

    @Test
    void aDeliveryReportIsPersistedThenAppliedToTheMessageAndPublished() {
        UUID tenant = UUID.randomUUID();
        var svc = service("in");
        var sent = svc.send(tenant, new SmsService.SendRequest(PHONE, otpText("654321"), null, null));
        String providerId = su.queryForObject("SELECT provider_message_id FROM outbound_messages WHERE id = ?", String.class, sent.messageId());

        byte[] report = ("[{\"id\":\"" + providerId + "\",\"status\":\"DELIVRD\"},{\"id\":\"" + providerId + "\",\"status\":\"SENT\"}]").getBytes(StandardCharsets.UTF_8);
        var dlrs = svc.provider().parseDlr(report);
        assertThat(dlrs).containsExactly(new SmsProvider.Dlr(providerId, "delivered", null)); // intermediate states are dropped
        svc.storeDlrs(dlrs);
        svc.storeDlrs(dlrs); // the BSP retried: nothing new stored
        assertThat(su.queryForObject("SELECT count(*) FROM inbound_events WHERE dedupe_key = ?", Integer.class, "sms:bsp:" + providerId + ":delivered")).isEqualTo(1);
        assertThat(su.queryForObject("SELECT tenant_id IS NULL AND status = 'pending' FROM inbound_events WHERE dedupe_key = ?", Boolean.class,
                "sms:bsp:" + providerId + ":delivered")).as("acked before any processing").isTrue();

        assertThat(svc.drainDlrs()).isGreaterThanOrEqualTo(1);
        assertThat(su.queryForObject("SELECT status FROM outbound_messages WHERE id = ?", String.class, sent.messageId())).isEqualTo("delivered");
        assertThat(su.queryForObject("SELECT tenant_id FROM inbound_events WHERE dedupe_key = ?", UUID.class, "sms:bsp:" + providerId + ":delivered")).isEqualTo(tenant);

        // a late 'failed' never undoes 'delivered'
        svc.storeDlrs(List.of(new SmsProvider.Dlr(providerId, "failed", "EXPIRED")));
        svc.drainDlrs();
        assertThat(su.queryForObject("SELECT status FROM outbound_messages WHERE id = ?", String.class, sent.messageId())).isEqualTo("delivered");

        var published = valkey.opsForStream().range("ev:" + REGION + ":comms", Range.unbounded()).stream()
                .map(r -> JSON.readTree(String.valueOf(r.getValue().get("ce"))))
                .filter(ce -> ce.path("data").path("messageId").asString().equals(sent.messageId().toString())).toList();
        assertThat(published).hasSize(1);
        assertThat(published.get(0).path("type").asString()).isEqualTo("comms.message.status_changed.v1");
        assertThat(published.get(0).path("data").path("channel").asString()).isEqualTo("sms");
        assertThat(published.get(0).path("data").path("status").asString()).isEqualTo("delivered");
    }

    @Test
    void com015_aDeliveryReportPricesTheMessageOnceWithTheBspsOwnCostOrThePriceList() {
        UUID tenant = UUID.randomUUID();
        var svc = service("in");
        var withCost = svc.send(tenant, new SmsService.SendRequest(PHONE, otpText("111222"), null, null));
        var listPrice = svc.send(tenant, new SmsService.SendRequest(PHONE, otpText("333444"), null, null));
        String id1 = su.queryForObject("SELECT provider_message_id FROM outbound_messages WHERE id = ?", String.class, withCost.messageId());
        String id2 = su.queryForObject("SELECT provider_message_id FROM outbound_messages WHERE id = ?", String.class, listPrice.messageId());

        var dlrs = svc.provider().parseDlr(("[{\"id\":\"" + id1 + "\",\"status\":\"DELIVRD\",\"cost\":\"0.18\"},{\"id\":\"" + id2 + "\",\"status\":\"DELIVRD\"}]")
                .getBytes(StandardCharsets.UTF_8));
        assertThat(dlrs).containsExactly(new SmsProvider.Dlr(id1, "delivered", null, 180000L), new SmsProvider.Dlr(id2, "delivered", null));
        svc.storeDlrs(dlrs);
        svc.drainDlrs();

        assertThat(su.queryForMap("SELECT pricing_category, currency, cost_micros, cost_minor, priced_at IS NOT NULL AS priced FROM outbound_messages WHERE id = ?", withCost.messageId()))
                .containsEntry("pricing_category", "otp").containsEntry("currency", "INR").containsEntry("cost_micros", 180000L).containsEntry("cost_minor", 18L).containsEntry("priced", true);
        // no cost in the report: the price list row (0 until the BSP rate is set), still counted under its DLT category
        assertThat(su.queryForMap("SELECT pricing_category, cost_micros FROM outbound_messages WHERE id = ?", listPrice.messageId()))
                .containsEntry("pricing_category", "otp").containsEntry("cost_micros", 0L);
        assertThat(BspSmsProvider.costMicros(JSON.readTree("\"-1\""))).isNull();
        assertThat(BspSmsProvider.costMicros(JSON.readTree("\"abc\""))).isNull();
        assertThat(BspSmsProvider.costMicros(JSON.readTree("0.125"))).isEqualTo(125000L);
    }

    @Test
    void aReportForAMessageNotYetKnownStaysPendingThenIsIgnored() {
        var svc = service("in");
        String unknown = "bsp-unknown-" + UUID.randomUUID();
        svc.storeDlrs(List.of(new SmsProvider.Dlr(unknown, "delivered", null)));
        svc.drainDlrs();
        assertThat(su.queryForObject("SELECT status FROM inbound_events WHERE dedupe_key = ?", String.class, "sms:bsp:" + unknown + ":delivered")).isEqualTo("pending");
        for (int i = 0; i < SmsService.DLR_MAX_ATTEMPTS; i++) {
            svc.drainDlrs();
        }
        assertThat(su.queryForObject("SELECT status FROM inbound_events WHERE dedupe_key = ?", String.class, "sms:bsp:" + unknown + ":delivered")).isEqualTo("ignored");
    }

    @Test
    void recipientMustBeAnIndianMobile() {
        assertThatThrownBy(() -> service("in").send(UUID.randomUUID(), new SmsService.SendRequest("+966501234567", otpText("1"), null, null))).hasMessage("RECIPIENT_INVALID");
        assertThat(bspRequests).isEmpty();
    }
}
