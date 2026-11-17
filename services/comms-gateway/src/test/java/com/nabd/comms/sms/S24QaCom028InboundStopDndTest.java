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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * S24 QA t1, COM-028 (cases C28-G01..G03): the inbound (MO) webhook with the real SmsService and database behind it (a forged or
 * missing proof stores nothing), a replayed MO id relayed to Core once even with two instances draining at once, and the DND
 * cache failing closed for promotional SMS while OTP and service SMS still go.
 */
class S24QaCom028InboundStopDndTest {

    static final ObjectMapper JSON = JsonMapper.builder().build();
    static final String SECRET = "qa-mo-secret";
    static final String CORE_TOKEN = "qa-core-token";
    static final String OTP_BODY = "{#var#} is your Nabd verification code. Valid for 10 minutes. Do not share it. - NABDHS";
    static final String SERVICE_BODY = "Your appointment details: {#var#} - NABDHS";
    static final String PROMO_BODY = "Health check camp on {#var#}. Reply STOP to opt out.";
    static final String PROMO_TEXT = "Health check camp on 21 Nov. Reply STOP to opt out.";

    static JdbcTemplate su;
    static JdbcTemplate app;
    static TransactionTemplate tx;
    static FieldCrypto crypto;
    static StringRedisTemplate valkey;
    static HttpServer stub;
    static final List<JsonNode> smsSent = new CopyOnWriteArrayList<>();
    static final List<JsonNode> dndAsked = new CopyOnWriteArrayList<>();
    static final List<JsonNode> coreCalls = new CopyOnWriteArrayList<>();
    static final AtomicInteger dndStatus = new AtomicInteger(200);
    static volatile boolean dndAnswer = false;
    static volatile long coreDelayMs = 0;
    static final AtomicInteger seq = new AtomicInteger();

    SimpleMeterRegistry meters;
    SmsService svc;
    MockMvc mvc;
    String base;

    @BeforeAll
    static void start() throws Exception {
        String db = "s24qa28_" + UUID.randomUUID().toString().replace("-", "");
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
        stub.setExecutor(Executors.newFixedThreadPool(8));
        stub.createContext("/sms", ex -> {
            smsSent.add(JSON.readTree(ex.getRequestBody().readAllBytes()));
            answer(ex, 200, "{\"id\":\"bsp-q28-" + seq.incrementAndGet() + "\"}");
        });
        stub.createContext("/dnd", ex -> {
            dndAsked.add(JSON.readTree(ex.getRequestBody().readAllBytes()));
            int st = dndStatus.get();
            answer(ex, st, st == 200 ? "{\"dnd\":" + dndAnswer + "}" : "{}");
        });
        stub.createContext("/internal/v1/consents:sms-keyword", ex -> {
            if (!CORE_TOKEN.equals(ex.getRequestHeaders().getFirst("X-Internal-Token"))) {
                answer(ex, 401, "{}");
                return;
            }
            coreCalls.add(JSON.readTree(ex.getRequestBody().readAllBytes()));
            try {
                Thread.sleep(coreDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            answer(ex, 200, "{\"optedOut\":true,\"confirm\":false}");
        });
        stub.start();
        su.update("INSERT INTO sms_templates (tenant_id, key, language, dlt_entity_id, dlt_template_id, header, category, body) VALUES (NULL, 'otp', 'en', 'e1', ?, 'NABDHS', 'otp', ?)",
                "1107q28o" + System.nanoTime(), OTP_BODY);
        su.update("INSERT INTO sms_templates (tenant_id, key, language, dlt_entity_id, dlt_template_id, header, category, body) VALUES (NULL, 'appt_reminder', 'en', 'e1', ?, 'NABDHS', 'service_implicit', ?)",
                "1107q28s" + System.nanoTime(), SERVICE_BODY);
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
        dndStatus.set(200);
        dndAnswer = false;
        coreDelayMs = 0;
        meters = new SimpleMeterRegistry();
        base = "http://127.0.0.1:" + stub.getAddress().getPort();
        svc = service();
        mvc = MockMvcBuilders.standaloneSetup(new SmsController(svc, "internal", SECRET, "", meters)).build();
    }

    SmsService service() {
        var props = new CommsConsumerProperties("s24qa28", new CommsConsumerProperties.Consumer(true, 50, 5, 60000));
        return new SmsService(app, tx, crypto, new BspSmsProvider("bsp", base, "k", JSON), new SendGate(new ConsentSnapshotStore(app), app, meters, Clock.systemUTC()),
                new StreamEventPublisher(valkey, props, JSON, Clock.systemUTC()), JSON, meters, Clock.systemUTC(), "in", Duration.ofHours(24));
    }

    static String sign(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    static String phone() {
        return "+9197" + (10000000 + (int) (Math.random() * 89999999));
    }

    int stored() {
        return su.queryForObject("SELECT count(*) FROM inbound_events WHERE field = 'sms_mo'", Integer.class);
    }

    int inbound(byte[] body, String signature) throws Exception {
        var req = post("/v1/webhooks/sms/bsp/inbound").contentType("application/json").content(body);
        if (signature != null) {
            req = req.header("X-Signature", signature);
        }
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    // C28-G01: forged / missing / replayed-for-another-body signatures are 401 and nothing reaches the database or Core
    @Test
    void c28g01_aForgedOrMissingProofIs401AndNothingIsStored() throws Exception {
        String from = phone().substring(1);
        byte[] honest = ("{\"id\":\"mo-q-" + UUID.randomUUID() + "\",\"from\":\"" + from + "\",\"text\":\"STOP\"}").getBytes(StandardCharsets.UTF_8);
        byte[] tampered = ("{\"id\":\"mo-q-" + UUID.randomUUID() + "\",\"from\":\"" + phone().substring(1) + "\",\"text\":\"STOP\"}").getBytes(StandardCharsets.UTF_8);
        int before = stored();
        List<Integer> codes = new ArrayList<>();
        codes.add(inbound(honest, null));                                           // no proof
        codes.add(inbound(honest, sign("not-the-secret", honest)));                 // wrong key
        codes.add(inbound(tampered, sign(SECRET, honest)));                         // a real signature of another body (replayed onto a forged body)
        codes.add(inbound(honest, sign(SECRET, honest).toUpperCase()));             // case-mangled
        codes.add(inbound(honest, "sha256="));                                      // empty
        var shared = post("/v1/webhooks/sms/bsp/inbound").contentType("application/json").content(honest).header("X-Webhook-Secret", SECRET);
        codes.add(mvc.perform(shared).andReturn().getResponse().getStatus());   // the shared secret without an allow-list
        int after = stored();
        int drained = new SmsStopService(app, tx, crypto, svc, JSON, meters, base, CORE_TOKEN).drain();
        System.out.println("C28-G01 forged codes=" + codes + " storedBefore=" + before + " storedAfter=" + after + " coreCalls=" + coreCalls.size() + " drained=" + drained);
        assertThat(codes).containsOnly(401);
        assertThat(after).isEqualTo(before);
        assertThat(coreCalls).isEmpty();
        // control: the honest signed request is accepted and stored
        assertThat(inbound(honest, sign(SECRET, honest))).isEqualTo(200);
        assertThat(stored()).isEqualTo(before + 1);
    }

    // C28-G02: the BSP retries the same MO id (and once with another text); two gateway instances drain at the same time
    @Test
    void c28g02_aReplayedMoIdIsStoredOnceAndRelayedToCoreOnce() throws Exception {
        String from = phone().substring(1), id = "mo-q-" + UUID.randomUUID();
        byte[] first = ("{\"id\":\"" + id + "\",\"from\":\"" + from + "\",\"text\":\"STOP\"}").getBytes(StandardCharsets.UTF_8);
        byte[] retried = ("[{\"id\":\"" + id + "\",\"from\":\"" + from + "\",\"text\":\"STOP ALL\"}]").getBytes(StandardCharsets.UTF_8);
        List<Integer> codes = List.of(inbound(first, sign(SECRET, first)), inbound(first, sign(SECRET, first)), inbound(retried, sign(SECRET, retried)));
        int rows = su.queryForObject("SELECT count(*) FROM inbound_events WHERE dedupe_key = ?", Integer.class, "sms-mo:bsp:" + id);
        coreDelayMs = 300;
        var a = new SmsStopService(app, tx, crypto, service(), JSON, meters, base, CORE_TOKEN);
        var b = new SmsStopService(app, tx, crypto, service(), JSON, meters, base, CORE_TOKEN);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier go = new CyclicBarrier(2);
        Future<Integer> fa = pool.submit(() -> { go.await(); return a.drain(); });
        Future<Integer> fb = pool.submit(() -> { go.await(); return b.drain(); });
        int done = fa.get() + fb.get();
        pool.shutdownNow();
        int later = a.drain() + b.drain();
        long relaysForId = coreCalls.stream().filter(c -> c.path("phone").asString("").equals(from)).count();
        String status = su.queryForObject("SELECT status FROM inbound_events WHERE dedupe_key = ?", String.class, "sms-mo:bsp:" + id);
        System.out.println("C28-G02 codes=" + codes + " storedRows=" + rows + " drained=" + done + " later=" + later + " coreRelays=" + relaysForId + " status=" + status);
        assertThat(codes).containsOnly(200);
        assertThat(rows).isEqualTo(1);
        assertThat(relaysForId).isEqualTo(1);
        assertThat(status).isEqualTo("done");
    }

    // C28-G03: stale or missing DND answers fail closed for promotional SMS only; a stale 'true' stays blocked; OTP and service still go
    @Test
    void c28g03_staleDndFailsClosedForPromotionalWhileOtpAndServiceStillGo() {
        UUID clinic = UUID.randomUUID(), patient = UUID.randomUUID();
        su.update("INSERT INTO sms_templates (tenant_id, key, language, dlt_entity_id, dlt_template_id, header, category, body) VALUES (?, 'camp', 'en', 'e2', ?, 'CLINIC', 'promotional', ?)",
                clinic, "1107q28p" + System.nanoTime(), PROMO_BODY);
        tx.executeWithoutResult(s -> new ConsentSnapshotStore(app).apply(clinic, patient, "marketing", true, 1));
        tx.executeWithoutResult(s -> new ConsentSnapshotStore(app).apply(clinic, patient, "messaging", true, 1));
        String staleFalse = phone(), staleTrue = phone(), fresh = phone(), never = phone();
        su.update("INSERT INTO sms_numbers (phone_hash, dnd, dnd_checked_at) VALUES (?, false, now() - interval '24 hours 1 minute')", (Object) crypto.hash(staleFalse));
        su.update("INSERT INTO sms_numbers (phone_hash, dnd, dnd_checked_at) VALUES (?, true, now() - interval '30 hours')", (Object) crypto.hash(staleTrue));
        su.update("INSERT INTO sms_numbers (phone_hash, dnd, dnd_checked_at) VALUES (?, false, now() - interval '23 hours 50 minutes')", (Object) crypto.hash(fresh));
        dndStatus.set(500); // the BSP scrub is down

        List<String> promo = new ArrayList<>();
        for (String to : List.of(staleFalse, staleTrue, never, fresh)) {
            var r = svc.send(clinic, new SmsService.SendRequest(to, PROMO_TEXT, patient, null));
            promo.add(su.queryForObject("SELECT status || '/' || COALESCE(error_code, '') FROM outbound_messages WHERE id = ?", String.class, r.messageId()));
        }
        int promoReachedBsp = smsSent.size();
        List<String> service = new ArrayList<>();
        int asked = dndAsked.size();
        for (String to : List.of(staleFalse, staleTrue, never)) {
            service.add(svc.send(clinic, new SmsService.SendRequest(to, OTP_BODY.replace("{#var#}", "246810"), null, null)).status());
            service.add(svc.send(clinic, new SmsService.SendRequest(to, SERVICE_BODY.replace("{#var#}", "Fri 11:30"), patient, null)).status());
        }
        System.out.println("C28-G03 promo(staleFalse,staleTrue,never,fresh23h50)=" + promo + " promoAtBsp=" + promoReachedBsp
                + " otp/service=" + service + " dndAskedByService=" + (dndAsked.size() - asked));
        assertThat(promo.subList(0, 3)).containsOnly("blocked/DND_CACHE_STALE");
        assertThat(promo.get(3)).as("a fresh 'not DND' answer is used without a scrub").isEqualTo("sent/");
        assertThat(promoReachedBsp).isEqualTo(1);
        assertThat(service).containsOnly("sent");
        assertThat(dndAsked.size() - asked).as("OTP and service SMS never ask the register").isZero();
    }
}
