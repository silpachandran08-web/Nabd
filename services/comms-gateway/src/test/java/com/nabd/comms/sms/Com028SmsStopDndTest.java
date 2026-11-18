package com.nabd.comms.sms;

import com.nabd.comms.consumer.CommsConsumerProperties;
import com.nabd.comms.events.StreamEventPublisher;
import com.nabd.comms.gate.SendGate;
import com.nabd.comms.inbox.FieldCrypto;
import com.nabd.comms.store.ConsentSnapshotStore;
import com.nabd.comms.testkit.Infra;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * COM-028 against a real Postgres (RLS as comms_app), a Valkey stand-in and local HTTP stubs for the BSP (send + DND scrub: the
 * fake DND register) and Core's consent API (POST /internal/v1/consents:sms-keyword).
 */
class Com028SmsStopDndTest {

    static final ObjectMapper JSON = JsonMapper.builder().build();
    static final String OTP_BODY = "{#var#} is your Nabd verification code. Valid for 10 minutes. Do not share it. - NABDHS";
    static final String CONFIRM_BODY = "You will not get promotional SMS from us any more. - NABDHS";
    static final String PROMO_BODY = "Free eye camp on {#var#}. Reply STOP to opt out.";
    static final String PROMO_TEXT = "Free eye camp on 20 Nov. Reply STOP to opt out.";
    static final String CORE_TOKEN = "core-test-token";

    static JdbcTemplate su;
    static JdbcTemplate app;
    static TransactionTemplate tx;
    static FieldCrypto crypto;
    static StringRedisTemplate valkey;
    static HttpServer stub;
    static final List<JsonNode> smsSent = new CopyOnWriteArrayList<>();
    static final List<JsonNode> dndAsked = new CopyOnWriteArrayList<>();
    static final List<JsonNode> coreCalls = new CopyOnWriteArrayList<>();
    static final Set<String> dndRegister = ConcurrentHashMap.newKeySet();
    static final AtomicInteger dndStatus = new AtomicInteger(200);
    static final AtomicInteger coreStatus = new AtomicInteger(200);
    static final AtomicReference<String> coreAnswer = new AtomicReference<>();
    static final AtomicInteger seq = new AtomicInteger();

    SimpleMeterRegistry meters;
    SmsService svc;
    SmsStopService stops;

    @BeforeAll
    static void start() throws Exception {
        String db = "com028_" + UUID.randomUUID().toString().replace("-", "");
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
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/sms", ex -> {
            smsSent.add(JSON.readTree(ex.getRequestBody().readAllBytes()));
            answer(ex, 200, "{\"id\":\"bsp-" + seq.incrementAndGet() + "\"}");
        });
        stub.createContext("/dnd", ex -> {
            JsonNode req = JSON.readTree(ex.getRequestBody().readAllBytes());
            dndAsked.add(req);
            int st = dndStatus.get();
            answer(ex, st, st == 200 ? "{\"dnd\":" + dndRegister.contains(req.path("to").asString()) + "}" : "{}");
        });
        stub.createContext("/internal/v1/consents:sms-keyword", ex -> {
            if (!CORE_TOKEN.equals(ex.getRequestHeaders().getFirst("X-Internal-Token"))) {
                answer(ex, 401, "{}");
                return;
            }
            coreCalls.add(JSON.readTree(ex.getRequestBody().readAllBytes()));
            answer(ex, coreStatus.get(), coreAnswer.get());
        });
        stub.start();
        su.update("INSERT INTO sms_templates (tenant_id, key, language, dlt_entity_id, dlt_template_id, header, category, body) VALUES (NULL, 'otp', 'en', 'e1', ?, 'NABDHS', 'otp', ?)",
                "1107o" + System.nanoTime(), OTP_BODY);
        su.update("INSERT INTO sms_templates (tenant_id, key, language, dlt_entity_id, dlt_template_id, header, category, body) VALUES (NULL, 'optout_confirm', 'en', 'e1', ?, 'NABDHS', 'service_implicit', ?)",
                "1107c" + System.nanoTime(), CONFIRM_BODY);
    }

    static void answer(HttpExchange ex, int status, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    @AfterAll
    static void stop() {
        stub.stop(0);
    }

    @BeforeEach
    void reset() {
        smsSent.clear();
        dndAsked.clear();
        coreCalls.clear();
        dndRegister.clear();
        dndStatus.set(200);
        coreStatus.set(200);
        coreAnswer.set("{\"optedOut\":true,\"confirm\":true}");
        meters = new SimpleMeterRegistry();
        var props = new CommsConsumerProperties("c028", new CommsConsumerProperties.Consumer(true, 50, 5, 60000));
        String base = "http://127.0.0.1:" + stub.getAddress().getPort();
        svc = new SmsService(app, tx, crypto, new BspSmsProvider("bsp", base, "k", JSON), new SendGate(new ConsentSnapshotStore(app), app, meters, Clock.systemUTC()),
                new StreamEventPublisher(valkey, props, JSON, Clock.systemUTC()), JSON, meters, Clock.systemUTC(), "in", Duration.ofHours(24));
        stops = new SmsStopService(app, tx, crypto, svc, JSON, meters, base, CORE_TOKEN);
    }

    static String phone() {
        return "+9198" + (10000000 + (int) (Math.random() * 89999999));
    }

    static UUID clinicWithPromoTemplate() {
        UUID clinic = UUID.randomUUID();
        su.update("INSERT INTO sms_templates (tenant_id, key, language, dlt_entity_id, dlt_template_id, header, category, body) VALUES (?, 'camp', 'en', 'e2', ?, 'CLINIC', 'promotional', ?)",
                clinic, "1107p" + System.nanoTime(), PROMO_BODY);
        return clinic;
    }

    UUID patientWithMarketing(UUID clinic) {
        UUID p = UUID.randomUUID();
        tx.executeWithoutResult(s -> new ConsentSnapshotStore(app).apply(clinic, p, "marketing", true, 1));
        return p;
    }

    String status(UUID messageId) {
        return su.queryForObject("SELECT status || '/' || COALESCE(error_code, '') FROM outbound_messages WHERE id = ?", String.class, messageId);
    }

    void mo(String id, String from, String text) {
        svc.storeMos(svc.provider().parseMo(("{\"id\":\"" + id + "\",\"from\":\"" + from + "\",\"text\":\"" + text + "\"}").getBytes(StandardCharsets.UTF_8)));
    }

    // AC1 + AC4 (SMS side): STOP -> Core withdraws marketing, one SMS confirmation, promotional SMS refused at send time, all inside 60 s
    @Test
    void anSmsStopWithdrawsMarketingThroughCoreConfirmsOnceAndBlocksPromotionalSmsWithin60s() {
        UUID clinic = clinicWithPromoTemplate();
        UUID patient = patientWithMarketing(clinic);
        String phone = phone();
        svc.send(clinic, new SmsService.SendRequest(phone, OTP_BODY.replace("{#var#}", "123456"), null, null)); // the SMS the patient replies to
        smsSent.clear();

        Instant received = Instant.now();
        String id = "mo-" + UUID.randomUUID();
        mo(id, phone.substring(1), "Stop"); // BSP sends 91XXXXXXXXXX without '+'
        mo(id, phone.substring(1), "Stop"); // the BSP retried: stored once
        assertThat(su.queryForObject("SELECT count(*) FROM inbound_events WHERE dedupe_key = ?", Integer.class, "sms-mo:bsp:" + id)).isEqualTo(1);

        assertThat(stops.drain()).isEqualTo(1);

        assertThat(coreCalls).hasSize(1);
        JsonNode call = coreCalls.get(0);
        assertThat(call.path("phone").asString()).isEqualTo(phone.substring(1));
        assertThat(call.path("text").asString()).isEqualTo("Stop");
        assertThat(call.path("tenantId").asString()).as("context = the clinic of the latest SMS").isEqualTo(clinic.toString());
        assertThat(smsSent).hasSize(1);
        assertThat(smsSent.get(0).path("text").asString()).isEqualTo(CONFIRM_BODY);
        assertThat(smsSent.get(0).path("to").asString()).isEqualTo(phone);
        assertThat(su.queryForObject("SELECT status FROM outbound_messages WHERE tenant_id = ? AND template_name = 'optout_confirm'", String.class, clinic)).isEqualTo("sent");
        assertThat(su.queryForObject("SELECT status FROM inbound_events WHERE dedupe_key = ?", String.class, "sms-mo:bsp:" + id)).isEqualTo("done");

        // the snapshot still says granted (Core's consent.changed.v1 not consumed yet): the number's opt-out refuses at send time anyway
        var promo = svc.send(clinic, new SmsService.SendRequest(phone, PROMO_TEXT, patient, null));
        assertThat(promo.status()).isEqualTo("blocked");
        assertThat(status(promo.messageId())).isEqualTo("blocked/OPTED_OUT");
        assertThat(su.queryForObject("SELECT reason FROM send_refusals WHERE message_id = ?", String.class, promo.messageId())).isEqualTo("OPTED_OUT");
        assertThat(smsSent).as("nothing promotional reached the BSP").hasSize(1);
        assertThat(Duration.between(received, Instant.now())).isLessThan(Duration.ofSeconds(60));
        // a service SMS to the same number still goes
        assertThat(svc.send(clinic, new SmsService.SendRequest(phone, OTP_BODY.replace("{#var#}", "654321"), null, null)).status()).isEqualTo("sent");

        // a repeated STOP: Core reports nothing changed, so no second confirmation
        coreAnswer.set("{\"optedOut\":true,\"confirm\":false}");
        smsSent.clear();
        mo("mo-" + UUID.randomUUID(), phone, "STOP");
        stops.drain();
        assertThat(smsSent).isEmpty();
    }

    @Test
    void coreUnreachableKeepsTheStopPendingUntilItAnswersAndNonKeywordsChangeNothing() {
        String phone = phone();
        coreStatus.set(503);
        String id = "mo-" + UUID.randomUUID();
        mo(id, phone, "STOP");
        assertThat(stops.drain()).isZero();
        assertThat(su.queryForObject("SELECT status || '/' || last_error_code FROM inbound_events WHERE dedupe_key = ?", String.class, "sms-mo:bsp:" + id))
                .isEqualTo("pending/CORE_UNAVAILABLE");
        coreStatus.set(200);
        assertThat(stops.drain()).isEqualTo(1);
        assertThat(coreCalls.get(coreCalls.size() - 1).hasNonNull("tenantId")).as("never SMSed: no clinic context").isFalse();
        assertThat(smsSent).as("no clinic to confirm under").isEmpty();
        assertThat(su.queryForObject("SELECT count(*) FROM sms_numbers WHERE phone_hash = ? AND marketing_opt_out_at IS NOT NULL", Integer.class, crypto.hash(phone))).isEqualTo(1);

        String other = phone();
        coreAnswer.set("{\"optedOut\":false,\"confirm\":false}");
        int before = coreCalls.size();
        mo("mo-" + UUID.randomUUID(), other, "Thank you doctor");
        String longId = "mo-" + UUID.randomUUID();
        mo(longId, other, "x".repeat(SmsStopService.MAX_KEYWORD + 1));
        assertThat(stops.drain()).isZero();
        assertThat(coreCalls).as("a long text never leaves the gateway").hasSize(before + 1);
        assertThat(su.queryForObject("SELECT status FROM inbound_events WHERE dedupe_key = ?", String.class, "sms-mo:bsp:" + longId)).isEqualTo("ignored");
        assertThat(su.queryForObject("SELECT count(*) FROM sms_numbers WHERE phone_hash = ?", Integer.class, crypto.hash(other))).isZero();
    }

    // AC2
    @Test
    void aPromotionalSmsToADndNumberIsBlockedWithReasonDndAndServiceSmsStillGo() {
        UUID clinic = clinicWithPromoTemplate();
        UUID patient = patientWithMarketing(clinic);
        String phone = phone();
        dndRegister.add(phone);

        var promo = svc.send(clinic, new SmsService.SendRequest(phone, PROMO_TEXT, patient, null));
        assertThat(status(promo.messageId())).isEqualTo("blocked/DND");
        assertThat(su.queryForObject("SELECT category || '/' || reason FROM send_refusals WHERE message_id = ?", String.class, promo.messageId())).isEqualTo("MARKETING/DND");
        assertThat(meters.counter("comms_send_refused_total", "reason", "DND").count()).isEqualTo(1);
        assertThat(smsSent).isEmpty();

        var otp = svc.send(clinic, new SmsService.SendRequest(phone, OTP_BODY.replace("{#var#}", "111111"), null, null));
        assertThat(otp.status()).isEqualTo("sent");
        assertThat(dndAsked).as("service SMS never asks the register").hasSize(1);

        svc.send(clinic, new SmsService.SendRequest(phone, PROMO_TEXT, patient, null));
        assertThat(dndAsked).as("a fresh answer is reused").hasSize(1);
        assertThat(su.queryForObject("SELECT count(*) FROM sms_numbers WHERE dnd", Integer.class)).isGreaterThanOrEqualTo(1);
        assertThat(su.queryForList("SELECT * FROM sms_numbers").toString()).doesNotContain(phone.substring(3));
    }

    // AC3
    @Test
    void aStaleOrMissingDndAnswerFailsClosedUntilAScrubRefreshesIt() {
        UUID clinic = clinicWithPromoTemplate();
        UUID patient = patientWithMarketing(clinic);
        String stale = phone(), unknown = phone();
        su.update("INSERT INTO sms_numbers (phone_hash, dnd, dnd_checked_at) VALUES (?, false, now() - interval '25 hours')", crypto.hash(stale));
        dndStatus.set(500);

        assertThat(status(svc.send(clinic, new SmsService.SendRequest(stale, PROMO_TEXT, patient, null)).messageId())).isEqualTo("blocked/DND_CACHE_STALE");
        assertThat(status(svc.send(clinic, new SmsService.SendRequest(unknown, PROMO_TEXT, patient, null)).messageId())).isEqualTo("blocked/DND_CACHE_STALE");
        assertThat(smsSent).isEmpty();

        dndStatus.set(200);
        var sent = svc.send(clinic, new SmsService.SendRequest(stale, PROMO_TEXT, patient, null));
        assertThat(sent.status()).isEqualTo("sent");
        assertThat(su.queryForObject("SELECT dnd_checked_at > now() - interval '1 minute' FROM sms_numbers WHERE phone_hash = ?", Boolean.class, crypto.hash(stale))).isTrue();
    }

    @Test
    void aPromotionalSmsWithNoPatientIsRefusedForWantOfConsent() {
        UUID clinic = clinicWithPromoTemplate();
        var promo = svc.send(clinic, new SmsService.SendRequest(phone(), PROMO_TEXT, null, null));
        assertThat(status(promo.messageId())).isEqualTo("blocked/no-marketing-consent");
        assertThat(smsSent).isEmpty();
        assertThat(dndAsked).isEmpty();
    }
}
