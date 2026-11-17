package com.nabd.comms.sms;

import com.nabd.comms.consumer.CommsConsumerProperties;
import com.nabd.comms.events.StreamEventPublisher;
import com.nabd.comms.gate.SendGate;
import com.nabd.comms.inbox.FieldCrypto;
import com.nabd.comms.inbox.InboxException;
import com.nabd.comms.send.StatusApplier;
import com.nabd.comms.store.ConsentSnapshotStore;
import com.nabd.comms.testkit.Infra;
import com.nabd.comms.testkit.TestClock;
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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S24 QA t1, COM-027 (cases C27-G01..G04): the one-SMS-per-original rule under real concurrency (two gateway instances in two
 * threads, a slow BSP so the first send is still in flight), BULK in every spelling and lane, a late 'delivered', and the desk's
 * 'Send as SMS' pressed twice at once. Real Postgres (RLS as comms_app) and a BSP stub, as in SmsFallbackTest.
 */
class S24QaCom027FallbackRaceTest {

    static final ObjectMapper JSON = JsonMapper.builder().build();
    static final String OTP_BODY = "{#var#} is your Nabd verification code. Valid for 10 minutes. Do not share it. - NABDHS";
    static final String REMINDER_BODY = "Your appointment details: {#var#} - NABDHS";
    static final String PHONE = "+919812345678";

    static JdbcTemplate su;
    static JdbcTemplate app;
    static TransactionTemplate tx;
    static FieldCrypto crypto;
    static StringRedisTemplate valkey;
    static HttpServer bsp;
    static final List<JsonNode> bspRequests = new CopyOnWriteArrayList<>();
    static final AtomicInteger seq = new AtomicInteger();
    static volatile long bspDelayMs = 0;

    TestClock clock;

    @BeforeAll
    static void start() throws Exception {
        String db = "s24qa27_" + UUID.randomUUID().toString().replace("-", "");
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
        bsp.setExecutor(Executors.newFixedThreadPool(8));
        bsp.createContext("/sms", ex -> {
            bspRequests.add(JSON.readTree(ex.getRequestBody().readAllBytes()));
            try {
                Thread.sleep(bspDelayMs); // a slow BSP: the first send is still in flight when the second instance looks
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] out = ("{\"id\":\"bsp-qa-" + seq.incrementAndGet() + "\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        bsp.start();
        su.update("INSERT INTO sms_templates (tenant_id, key, language, dlt_entity_id, dlt_template_id, header, category, body) VALUES (NULL, 'otp', 'en', 'e1', ?, 'NABDHS', 'otp', ?)",
                "1107qo" + System.nanoTime(), OTP_BODY);
        su.update("INSERT INTO sms_templates (tenant_id, key, language, dlt_entity_id, dlt_template_id, header, category, body) VALUES (NULL, 'appt_reminder', 'en', 'e1', ?, 'NABDHS', 'service_implicit', ?)",
                "1107qr" + System.nanoTime(), REMINDER_BODY);
    }

    @AfterAll
    static void stop() {
        bsp.stop(0);
    }

    @BeforeEach
    void reset() {
        bspRequests.clear();
        bspDelayMs = 0;
        clock = new TestClock(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        su.update("DELETE FROM channel_reachability");
        su.update("UPDATE outbound_messages SET fallback_decided_at = now() WHERE fallback_decided_at IS NULL");
    }

    SmsFallback instance() {
        var meters = new SimpleMeterRegistry();
        var props = new CommsConsumerProperties("s24qa27", new CommsConsumerProperties.Consumer(true, 50, 5, 60000));
        var sms = new SmsService(app, tx, crypto, new BspSmsProvider("bsp", "http://127.0.0.1:" + bsp.getAddress().getPort(), "k", JSON),
                new SendGate(new ConsentSnapshotStore(app), app, meters, clock), new StreamEventPublisher(valkey, props, JSON, clock), JSON, meters, clock, "in", Duration.ofHours(24));
        return new SmsFallback(app, tx, crypto, sms, meters, clock);
    }

    UUID whatsapp(UUID tenant, UUID requestId, String priority, String status, String template, List<String> params, Instant updatedAt, UUID patient, UUID thread) {
        UUID id = UUID.randomUUID();
        su.update("""
                INSERT INTO outbound_messages (id, tenant_id, request_id, channel, patient_ref, to_address_enc, to_hash, template_name, language, params_enc,
                  priority, status, thread_id, created_at, updated_at) VALUES (?,?,?, 'whatsapp', ?,?,?,?, 'en', ?, ?,?,?,?,?)""",
                id, tenant, requestId, patient, crypto.encrypt(PHONE), crypto.hash(PHONE), template, crypto.encrypt(String.join("\u001f", params)),
                priority, status, thread, Timestamp.from(updatedAt), Timestamp.from(updatedAt));
        return id;
    }

    int smsFor(UUID original) {
        return su.queryForObject("SELECT count(*) FROM outbound_messages WHERE channel = 'sms' AND fallback_of = ?", Integer.class, original);
    }

    static <T> List<T> together(List<Callable<T>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        CyclicBarrier go = new CyclicBarrier(jobs.size());
        try {
            List<Future<T>> fs = new ArrayList<>();
            for (Callable<T> j : jobs) {
                fs.add(pool.submit(() -> {
                    go.await();
                    return j.call();
                }));
            }
            List<T> out = new ArrayList<>();
            for (Future<T> f : fs) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    // C27-G01: two instances tick at the same instant over OTPs and failed reminders (with and without a request id), BSP slow
    @Test
    void c27g01_twoInstancesTickingInParallelSendExactlyOneSmsPerOriginal() throws Exception {
        UUID tenant = UUID.randomUUID();
        List<UUID> originals = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            originals.add(whatsapp(tenant, UUID.randomUUID(), "OTP", "sent", "otp", List.of("10000" + i), clock.instant(), null, null));
            originals.add(whatsapp(tenant, UUID.randomUUID(), "REMINDER", "failed", "appt_reminder", List.of("slot " + i), clock.instant(), null, null));
            originals.add(whatsapp(tenant, null, "REMINDER", "failed", "appt_reminder", List.of("legacy " + i), clock.instant(), null, null)); // backfilled: no request id
        }
        clock.advance(Duration.ofSeconds(31));
        bspDelayMs = 300;
        SmsFallback a = instance(), b = instance();
        List<Integer> sent = together(List.<Callable<Integer>>of(a::tick, b::tick));
        // a third pass after both: nothing left to do
        int again = a.tick() + b.tick();
        int[] perOriginal = originals.stream().mapToInt(this::smsFor).toArray();
        System.out.println("C27-G01 originals=" + originals.size() + " tickA=" + sent.get(0) + " tickB=" + sent.get(1) + " again=" + again
                + " bspRequests=" + bspRequests.size() + " smsPerOriginal=" + java.util.Arrays.toString(perOriginal));
        assertThat(perOriginal).as("exactly one SMS row per original").containsOnly(1);
        assertThat(bspRequests).as("exactly one BSP call per original").hasSize(originals.size());
        assertThat(again).isZero();
    }

    // C27-G02: BULK never falls back, in any spelling, any lane, and not via the SMS-first path either
    @Test
    void c27g02_bulkNeverFallsBack() {
        UUID tenant = UUID.randomUUID();
        List<UUID> bulk = List.of(
                whatsapp(tenant, UUID.randomUUID(), "BULK", "failed", "appt_reminder", List.of("r1"), clock.instant(), null, null),
                whatsapp(tenant, UUID.randomUUID(), "Bulk", "sent", "appt_reminder", List.of("r2"), clock.instant(), null, null),
                whatsapp(tenant, UUID.randomUUID(), "bulk", "queued", "appt_reminder", List.of("r3"), clock.instant(), null, null));
        // the number is known not to be on WhatsApp: the SMS-first lane must not take BULK either
        su.update("INSERT INTO channel_reachability (phone_hash, whatsapp_reachable, last_checked_at, reason_code) VALUES (?, false, now(), '131026')", (Object) crypto.hash(PHONE));
        var fb = instance();
        int total = 0;
        for (int m = 0; m < 50; m++) { // 50 min of 1-minute ticks
            clock.advance(Duration.ofMinutes(1));
            total += fb.tick();
        }
        int rows = bulk.stream().mapToInt(this::smsFor).sum();
        System.out.println("C27-G02 bulk ticks sent=" + total + " smsRows=" + rows + " bspRequests=" + bspRequests.size());
        assertThat(total).isZero();
        assertThat(rows).isZero();
        assertThat(bspRequests).isEmpty();
        // observation for the report: the desk action does not look at the lane
        UUID thread = UUID.randomUUID();
        su.update("INSERT INTO inbox_threads (id, tenant_id, sender_account_id, phone_hash, phone_enc) VALUES (?,?,?,?,?)",
                thread, tenant, UUID.randomUUID(), crypto.hash(PHONE), crypto.encrypt(PHONE));
        UUID patient = UUID.randomUUID();
        su.update("INSERT INTO channel_consent_snapshot (tenant_id, patient_ref, purpose, granted, version) VALUES (?,?, 'messaging', true, 1)", tenant, patient);
        UUID deskBulk = whatsapp(tenant, UUID.randomUUID(), "BULK", "failed", "appt_reminder", List.of("recall"), clock.instant(), patient, thread);
        String desk;
        try {
            desk = instance().sendAsSms(tenant, deskBulk, UUID.randomUUID()).status();
        } catch (InboxException e) {
            desk = e.code();
        }
        System.out.println("C27-G02 observation desk 'Send as SMS' on a failed BULK message -> " + desk);
    }

    // C27-G03: 'delivered' (and 'read') after the SMS: no second send by the timer, the desk is told it already went
    @Test
    void c27g03_aLateDeliveredOrReadNeverCausesASecondSend() {
        UUID tenant = UUID.randomUUID(), thread = UUID.randomUUID(), patient = UUID.randomUUID();
        su.update("INSERT INTO inbox_threads (id, tenant_id, sender_account_id, phone_hash, phone_enc) VALUES (?,?,?,?,?)",
                thread, tenant, UUID.randomUUID(), crypto.hash(PHONE), crypto.encrypt(PHONE));
        su.update("INSERT INTO channel_consent_snapshot (tenant_id, patient_ref, purpose, granted, version) VALUES (?,?, 'messaging', true, 1)", tenant, patient);
        UUID wa = whatsapp(tenant, UUID.randomUUID(), "REMINDER", "failed", "appt_reminder", List.of("Tue 10:00"), clock.instant(), patient, thread);
        var fb = instance();
        clock.advance(Duration.ofSeconds(5));
        assertThat(fb.tick()).isEqualTo(1);
        su.update("UPDATE outbound_messages SET provider_message_id = 'wamid.qa-late', status = 'sent' WHERE id = ?", wa); // a late retry by Meta
        boolean moved = Boolean.TRUE.equals(tx.execute(s -> {
            app.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
            boolean d = new StatusApplier(app).apply("wamid.qa-late", "delivered");
            new StatusApplier(app).apply("wamid.qa-late", "read");
            return d;
        }));
        int later = 0;
        for (int m = 0; m < 12; m++) {
            clock.advance(Duration.ofMinutes(1));
            later += fb.tick() + instance().tick();
        }
        String desk;
        try {
            desk = fb.sendAsSms(tenant, wa, UUID.randomUUID()).status();
        } catch (InboxException e) {
            desk = e.code();
        }
        System.out.println("C27-G03 deliveredApplied=" + moved + " laterTicks=" + later + " smsRows=" + smsFor(wa) + " bspRequests=" + bspRequests.size() + " desk=" + desk);
        assertThat(later).isZero();
        assertThat(smsFor(wa)).isEqualTo(1);
        assertThat(bspRequests).hasSize(1);
        assertThat(desk).isIn("ALREADY_SENT_BY_SMS", "MESSAGE_NOT_FAILED");
    }

    // C27-G04: reception double-clicks 'Send as SMS' (two requests at the same instant, slow BSP)
    @Test
    void c27g04_deskSendAsSmsPressedTwiceAtOnceSendsOneSms() throws Exception {
        UUID tenant = UUID.randomUUID(), thread = UUID.randomUUID(), patient = UUID.randomUUID(), staff = UUID.randomUUID();
        su.update("INSERT INTO inbox_threads (id, tenant_id, sender_account_id, phone_hash, phone_enc) VALUES (?,?,?,?,?)",
                thread, tenant, UUID.randomUUID(), crypto.hash(PHONE), crypto.encrypt(PHONE));
        su.update("INSERT INTO channel_consent_snapshot (tenant_id, patient_ref, purpose, granted, version) VALUES (?,?, 'messaging', true, 1)", tenant, patient);
        UUID wa = whatsapp(tenant, UUID.randomUUID(), "normal", "failed", "appt_reminder", List.of("https://nabd.in/s/Qa24"), clock.instant(), patient, thread);
        bspDelayMs = 300;
        SmsFallback a = instance(), b = instance();
        Callable<String> press1 = () -> press(a, tenant, wa, staff), press2 = () -> press(b, tenant, wa, staff);
        List<String> answers = together(List.of(press1, press2));
        int audits = su.queryForObject("SELECT count(*) FROM inbox_audit WHERE thread_id = ? AND action = 'send_as_sms'", Integer.class, thread);
        System.out.println("C27-G04 answers=" + answers + " smsRows=" + smsFor(wa) + " bspRequests=" + bspRequests.size() + " auditRows=" + audits);
        assertThat(smsFor(wa)).as("one SMS row").isEqualTo(1);
        assertThat(bspRequests).as("one BSP call").hasSize(1);
        assertThat(answers.stream().filter(x -> x.startsWith("201")).count()).as("exactly one press reports a send").isEqualTo(1);
        assertThat(audits).as("one send_as_sms audit row for one SMS").isEqualTo(1);
    }

    private static String press(SmsFallback f, UUID tenant, UUID wa, UUID staff) {
        try {
            var s = f.sendAsSms(tenant, wa, staff);
            return "201 " + s.status() + " " + s.messageId();
        } catch (InboxException e) {
            return e.status().value() + " " + e.code();
        }
    }
}
