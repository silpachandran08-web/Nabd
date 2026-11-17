package com.nabd.comms.sms;

import com.nabd.comms.consumer.CommsConsumerProperties;
import com.nabd.comms.events.StreamEventPublisher;
import com.nabd.comms.gate.SendGate;
import com.nabd.comms.inbox.FieldCrypto;
import com.nabd.comms.inbox.InboxException;
import com.nabd.comms.inbox.InboxService;
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
import org.springframework.beans.factory.support.StaticListableBeanFactory;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** COM-027 against a real Postgres (RLS as comms_app) and a BSP stub: the fallback timers, reachability, BULK, the desk action, chaos. */
class SmsFallbackTest {

    static final ObjectMapper JSON = JsonMapper.builder().build();
    static final String OTP_BODY = "{#var#} is your Nabd verification code. Valid for 10 minutes. Do not share it. - NABDHS";
    static final String REMINDER_BODY = "Your appointment details: {#var#} - NABDHS";
    static final String PHONE = "+919876543210";

    static JdbcTemplate su;
    static JdbcTemplate app;
    static TransactionTemplate tx;
    static FieldCrypto crypto;
    static StringRedisTemplate valkey;
    static HttpServer bsp;
    static final List<JsonNode> bspRequests = new CopyOnWriteArrayList<>();
    static final AtomicInteger seq = new AtomicInteger();

    TestClock clock;

    @BeforeAll
    static void start() throws Exception {
        String db = "smsfb_" + UUID.randomUUID().toString().replace("-", "");
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
            byte[] out = ("{\"id\":\"bsp-fb-" + seq.incrementAndGet() + "\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        bsp.start();
        su.update("INSERT INTO sms_templates (tenant_id, key, language, dlt_entity_id, dlt_template_id, header, category, body) VALUES (NULL, 'otp', 'en', 'e1', ?, 'NABDHS', 'otp', ?)",
                "1107fo" + System.nanoTime(), OTP_BODY);
        su.update("INSERT INTO sms_templates (tenant_id, key, language, dlt_entity_id, dlt_template_id, header, category, body) VALUES (NULL, 'appt_reminder', 'en', 'e1', ?, 'NABDHS', 'service_implicit', ?)",
                "1107fr" + System.nanoTime(), REMINDER_BODY);
    }

    @AfterAll
    static void stop() {
        bsp.stop(0);
    }

    @BeforeEach
    void reset() {
        bspRequests.clear();
        clock = new TestClock(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        su.update("DELETE FROM channel_reachability");
        // the tick scans every tenant: settle rows earlier tests left due (e.g. the desk's KSA refusal) so each test counts only its own
        su.update("UPDATE outbound_messages SET fallback_decided_at = now() WHERE fallback_decided_at IS NULL");
    }

    SmsService sms(String region) {
        var meters = new SimpleMeterRegistry();
        var props = new CommsConsumerProperties("smsfb", new CommsConsumerProperties.Consumer(true, 50, 5, 60000));
        return new SmsService(app, tx, crypto, new BspSmsProvider("bsp", "http://127.0.0.1:" + bsp.getAddress().getPort(), "k", JSON),
                new SendGate(new ConsentSnapshotStore(app), app, meters, clock), new StreamEventPublisher(valkey, props, JSON, clock), JSON, meters, clock, region);
    }

    SmsFallback fallback(String region) {
        return new SmsFallback(app, tx, crypto, sms(region), new SimpleMeterRegistry(), clock);
    }

    SmsFallback fallback() {
        return fallback("in");
    }

    /** A WhatsApp row as the sender leaves it; updatedAt = when it was sent / queued / failed. */
    UUID whatsapp(UUID tenant, String priority, String status, String template, List<String> params, Instant updatedAt, String errorCode, UUID patient, UUID thread) {
        UUID id = UUID.randomUUID();
        su.update("""
                INSERT INTO outbound_messages (id, tenant_id, request_id, channel, patient_ref, to_address_enc, to_hash, template_name, language, params_enc,
                  priority, status, error_code, thread_id, created_at, updated_at) VALUES (?,?,?, 'whatsapp', ?,?,?,?, 'en', ?, ?,?,?,?,?,?)""",
                id, tenant, UUID.randomUUID(), patient, crypto.encrypt(PHONE), crypto.hash(PHONE), template, crypto.encrypt(String.join("\u001f", params)),
                priority, status, errorCode, thread, Timestamp.from(updatedAt), Timestamp.from(updatedAt));
        return id;
    }

    UUID otp(UUID tenant, String code, Instant sentAt) {
        return whatsapp(tenant, "OTP", "sent", "otp", List.of(code), sentAt, null, null, null);
    }

    int smsFor(UUID original) {
        return su.queryForObject("SELECT count(*) FROM outbound_messages WHERE channel = 'sms' AND fallback_of = ?", Integer.class, original);
    }

    @Test
    void anOtpNotDeliveredAfter30sGetsExactlyOneSmsOtpUnderTheSameRequest() {
        UUID tenant = UUID.randomUUID();
        UUID wa = otp(tenant, "482913", clock.instant());
        var fb = fallback();

        clock.advance(Duration.ofSeconds(29));
        assertThat(fb.tick()).isZero();
        assertThat(bspRequests).isEmpty();

        clock.advance(Duration.ofSeconds(1)); // the 30 s timer fires
        assertThat(fb.tick()).isEqualTo(1);
        assertThat(bspRequests).hasSize(1);
        assertThat(bspRequests.get(0).path("text").asString()).isEqualTo(OTP_BODY.replace("{#var#}", "482913"));
        var sms = su.queryForMap("SELECT s.status, s.request_id = w.request_id same_request, s.channel FROM outbound_messages s JOIN outbound_messages w ON w.id = s.fallback_of WHERE w.id = ?", wa);
        assertThat(sms.get("status")).as("the desk and the login screen read status sent + channel sms = 'Sent by SMS'").isEqualTo("sent");
        assertThat(sms.get("same_request")).isEqualTo(true);
        assertThat(sms.get("channel")).isEqualTo("sms");
        assertThat(su.queryForObject("SELECT fallback_decided_at IS NOT NULL FROM outbound_messages WHERE id = ?", Boolean.class, wa)).isTrue();

        clock.advance(Duration.ofMinutes(2));
        assertThat(fb.tick()).isZero();
        assertThat(bspRequests).as("one fallback per request").hasSize(1);
    }

    @Test
    void aReminderFailedAt1000IsSentBySmsBy1005WithTheLinkOnlyAndBothRowsAreLinked() {
        UUID tenant = UUID.randomUUID(), thread = thread(tenant);
        UUID wa = whatsapp(tenant, "REMINDER", "failed", "appt_reminder", List.of("Dr Rao", "https://nabd.in/s/Ab12"), clock.instant(), "131049", null, thread);
        var fb = fallback();

        clock.advance(Duration.ofSeconds(5)); // the next 5 s tick
        assertThat(fb.tick()).isEqualTo(1);
        assertThat(bspRequests).hasSize(1);
        assertThat(bspRequests.get(0).path("text").asString()).as("secure-link messages fall back with the link only")
                .isEqualTo("Your appointment details: https://nabd.in/s/Ab12 - NABDHS");
        assertThat(smsFor(wa)).isEqualTo(1);

        var items = new InboxService(app, tx, crypto, null, new StaticListableBeanFactory().getBeanProvider(com.nabd.comms.inbox.PatientDirectory.class), clock)
                .messages(tenant, thread, null, 10).items();
        assertThat(items).extracting(InboxService.MessageItem::channel).containsExactlyInAnyOrder("whatsapp", "sms");
        assertThat(items).filteredOn(m -> "sms".equals(m.channel())).extracting(InboxService.MessageItem::status).containsExactly("sent");
    }

    @Test
    void aReminderStillUndeliveredAfter5MinFallsBackButNotBefore() {
        UUID tenant = UUID.randomUUID();
        UUID wa = whatsapp(tenant, "REMINDER", "sent", "appt_reminder", List.of("Tue 10:00"), clock.instant(), null, null, null);
        var fb = fallback();
        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        assertThat(fb.tick()).isZero();
        clock.advance(Duration.ofSeconds(1));
        assertThat(fb.tick()).isEqualTo(1);
        assertThat(bspRequests.get(0).path("text").asString()).isEqualTo("Your appointment details: Tue 10:00 - NABDHS");
        assertThat(smsFor(wa)).isEqualTo(1);
    }

    @Test
    void aDeliveredReportStopsTheFallbackAndALateDeliveredNeverCausesASecondSend() {
        UUID tenant = UUID.randomUUID();
        var fb = fallback();
        var other = fallback(); // a second gateway instance ticking at the same time

        UUID early = otp(tenant, "111111", clock.instant());
        delivered(tenant, early, "wamid.early");
        clock.advance(Duration.ofMinutes(1));
        assertThat(fb.tick() + other.tick()).isZero();
        assertThat(smsFor(early)).as("delivered before the timer: no SMS").isZero();

        UUID late = otp(tenant, "222222", clock.instant());
        clock.advance(Duration.ofSeconds(31));
        assertThat(fb.tick()).isEqualTo(1);
        delivered(tenant, late, "wamid.late"); // WhatsApp delivers after all
        clock.advance(Duration.ofMinutes(10));
        assertThat(fb.tick() + other.tick()).isZero();
        assertThat(fb.fallBack(tenant, late)).as("even a direct retry of the decision sends nothing").isFalse();
        assertThat(smsFor(late)).isEqualTo(1);
        assertThat(bspRequests).hasSize(1);

        UUID raced = otp(tenant, "333333", clock.instant().minusSeconds(40));
        assertThat(fb.fallBack(tenant, raced)).isTrue();
        su.update("UPDATE outbound_messages SET fallback_decided_at = NULL WHERE id = ?", raced); // as if the mark was lost (crash, second instance)
        assertThat(other.fallBack(tenant, raced)).isFalse();
        assertThat(smsFor(raced)).isEqualTo(1);
        assertThat(bspRequests).hasSize(2);

        // two senders that both missed each other's row (no request id to share): fallback_of is unique, the second is refused before the BSP
        String text = OTP_BODY.replace("{#var#}", "444444");
        sms("in").send(tenant, new SmsService.SendRequest(PHONE, text, null, null), early);
        assertThatThrownBy(() -> sms("in").send(tenant, new SmsService.SendRequest(PHONE, text, null, null), early)).hasMessage("DUPLICATE_REQUEST");
        assertThat(smsFor(early)).isEqualTo(1);
        assertThat(bspRequests).hasSize(3);
    }

    @Test
    void aNumberNotOnWhatsAppIsMarkedAndItsNextReminderGoesSmsFirstForEveryClinic() {
        UUID clinicA = UUID.randomUUID(), clinicB = UUID.randomUUID();
        var fb = fallback();
        whatsapp(clinicA, "REMINDER", "failed", "appt_reminder", List.of("Mon 09:00"), clock.instant(), "131026", null, null);
        assertThat(fb.tick()).isEqualTo(1);
        assertThat(su.queryForObject("SELECT NOT whatsapp_reachable AND reason_code = '131026' FROM channel_reachability WHERE phone_hash = ?", Boolean.class,
                (Object) crypto.hash(PHONE))).isTrue();
        assertThat(fb.smsFirst(crypto.hash(PHONE))).isTrue();

        UUID next = whatsapp(clinicB, "REMINDER", "queued", "appt_reminder", List.of("Wed 11:00"), clock.instant(), null, null, null);
        assertThat(fb.tick()).as("no 5 min wait: straight to SMS").isEqualTo(1);
        assertThat(smsFor(next)).isEqualTo(1);
        assertThat(su.queryForObject("SELECT status || '/' || error_code FROM outbound_messages WHERE id = ?", String.class, next))
                .as("the WhatsApp copy never leaves").isEqualTo("cancelled/sent-by-sms");

        clock.advance(Duration.ofDays(31));
        assertThat(fb.smsFirst(crypto.hash(PHONE))).as("the mark expires: WhatsApp is tried again").isFalse();
    }

    @Test
    void aBulkRecallThatFailsOnWhatsAppIsNeverSentBySms() {
        UUID tenant = UUID.randomUUID();
        UUID failed = whatsapp(tenant, "BULK", "failed", "appt_reminder", List.of("recall"), clock.instant(), "131049", null, null);
        UUID silent = whatsapp(tenant, "bulk", "sent", "appt_reminder", List.of("recall"), clock.instant(), null, null, null);
        clock.advance(Duration.ofMinutes(30));
        assertThat(fallback().tick()).isZero();
        assertThat(bspRequests).isEmpty();
        assertThat(smsFor(failed) + smsFor(silent)).isZero();
    }

    @Test
    void historyOlderThanAnHourAndMessagesWithoutAnSmsTemplateAreLeftAlone() {
        UUID tenant = UUID.randomUUID();
        whatsapp(tenant, "REMINDER", "failed", "appt_reminder", List.of("x"), clock.instant().minus(Duration.ofHours(2)), null, null, null);
        UUID noTpl = whatsapp(tenant, "REMINDER", "failed", "session_text", List.of(), clock.instant(), null, null, null);
        assertThat(fallback().tick()).isZero();
        assertThat(bspRequests).isEmpty();
        assertThat(su.queryForObject("SELECT fallback_decided_at IS NOT NULL FROM outbound_messages WHERE id = ?", Boolean.class, noTpl)).isTrue();
    }

    @Test
    void deskSendAsSmsCarriesTheSameLinkOnceAndIsAuditedConsentAndRegionChecked() {
        UUID tenant = UUID.randomUUID(), thread = thread(tenant), staff = UUID.randomUUID(), patient = UUID.randomUUID();
        su.update("INSERT INTO channel_consent_snapshot (tenant_id, patient_ref, purpose, granted, version) VALUES (?,?, 'messaging', true, 1)", tenant, patient);
        UUID wa = whatsapp(tenant, "normal", "failed", "appt_reminder", List.of("Dr Rao", "https://nabd.in/s/Zz99"), clock.instant(), "131049", patient, thread);
        var fb = fallback();

        var sent = fb.sendAsSms(tenant, wa, staff);
        assertThat(sent.status()).isEqualTo("sent");
        assertThat(bspRequests.get(0).path("text").asString()).isEqualTo("Your appointment details: https://nabd.in/s/Zz99 - NABDHS");
        assertThat(su.queryForObject("SELECT count(*) FROM inbox_audit WHERE thread_id = ? AND action = 'send_as_sms' AND staff_ref = ?", Integer.class, thread, staff)).isEqualTo(1);
        assertThatThrownBy(() -> fb.sendAsSms(tenant, wa, staff)).isInstanceOfSatisfying(InboxException.class, e -> assertThat(e.code()).isEqualTo("ALREADY_SENT_BY_SMS"));
        assertThatThrownBy(() -> fb.sendAsSms(UUID.randomUUID(), wa, staff)).as("another clinic sees 404").hasMessage("MESSAGE_NOT_FOUND");

        UUID ok = whatsapp(tenant, "REMINDER", "delivered", "appt_reminder", List.of("x"), clock.instant(), null, patient, thread);
        assertThatThrownBy(() -> fb.sendAsSms(tenant, ok, staff)).hasMessage("MESSAGE_NOT_FAILED");

        UUID noConsent = whatsapp(tenant, "REMINDER", "failed", "appt_reminder", List.of("x"), clock.instant(), null, UUID.randomUUID(), thread);
        assertThat(fb.sendAsSms(tenant, noConsent, staff).status()).as("no messaging consent: refused at send time").isEqualTo("blocked");

        UUID ksa = whatsapp(tenant, "REMINDER", "failed", "appt_reminder", List.of("x"), clock.instant(), null, patient, thread);
        assertThatThrownBy(() -> fallback("sa").sendAsSms(tenant, ksa, staff)).hasMessage("SMS_DISABLED_FOR_REGION");
        assertThat(bspRequests).hasSize(1);
    }

    /** Chaos: Meta is down for 30 min, so nothing leaves on WhatsApp. Every reminder reaches the patient by SMS within 5 min, once. */
    @Test
    void metaDownFor30MinRemindersGoBySmsWithZeroDuplicates() {
        UUID tenant = UUID.randomUUID();
        var a = fallback();
        var b = fallback();
        List<UUID> reminders = new java.util.ArrayList<>();
        Instant end = clock.instant().plus(Duration.ofMinutes(35));
        for (int step = 0; clock.instant().isBefore(end); step++) {
            if (step % 4 == 0 && reminders.size() < 30) { // one reminder a minute while Meta is down
                reminders.add(whatsapp(tenant, "REMINDER", "queued", "appt_reminder", List.of("slot " + step), clock.instant(), null, null, null));
            }
            a.tick();
            b.tick();
            clock.advance(Duration.ofSeconds(15));
        }
        assertThat(reminders).hasSize(30);
        for (UUID r : reminders) {
            assertThat(smsFor(r)).isEqualTo(1);
            var row = su.queryForMap("SELECT w.status, extract(epoch FROM s.created_at - w.created_at) lag FROM outbound_messages w JOIN outbound_messages s ON s.fallback_of = w.id WHERE w.id = ?", r);
            assertThat(row.get("status")).as("Meta comes back: the WhatsApp copy is no longer queued").isEqualTo("cancelled");
            assertThat(((Number) row.get("lag")).doubleValue()).isLessThanOrEqualTo(Duration.ofMinutes(5).plusSeconds(15).toSeconds());
        }
        assertThat(bspRequests).hasSize(30);
        assertThat(bspRequests.stream().map(j -> j.path("reference").asString()).distinct().count()).isEqualTo(30);
    }

    @Test
    void fillUsesTheParamsInOrderOrTheLinkOnly() {
        assertThat(SmsFallback.fill("A {#var#} B {#var#}", List.of("1", "2", "3"))).isEqualTo("A 1 B 2");
        assertThat(SmsFallback.fill("Open {#var#}", List.of("Dr Rao", "https://nabd.in/s/x"))).isEqualTo("Open https://nabd.in/s/x");
        assertThat(SmsFallback.fill("A {#var#} B {#var#}", List.of("1"))).isNull();
        assertThat(SmsFallback.fill("No vars", List.of())).isEqualTo("No vars");
    }

    void delivered(UUID tenant, UUID id, String wamid) {
        su.update("UPDATE outbound_messages SET provider_message_id = ? WHERE id = ?", wamid, id);
        tx.executeWithoutResult(s -> {
            app.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
            assertThat(new StatusApplier(app).apply(wamid, "delivered")).isTrue();
        });
    }

    UUID thread(UUID tenant) {
        UUID id = UUID.randomUUID();
        su.update("INSERT INTO inbox_threads (id, tenant_id, sender_account_id, phone_hash, phone_enc) VALUES (?,?,?,?,?)",
                id, tenant, UUID.randomUUID(), crypto.hash(PHONE), crypto.encrypt(PHONE));
        return id;
    }
}
