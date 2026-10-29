package com.nabd.comms;

import com.nabd.comms.consumer.CommsConsumerProperties;
import com.nabd.comms.events.StreamEventPublisher;
import com.nabd.comms.inbox.FieldCrypto;
import com.nabd.comms.inbox.InboxException;
import com.nabd.comms.inbox.InboxService;
import com.nabd.comms.inbox.PatientDirectory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** COM-030 against a real Postgres (RLS as comms_app) and a Valkey stand-in. */
class InboxServiceTest {

    static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static JdbcTemplate su;
    static InboxService inbox;
    static FieldCrypto crypto;
    static StringRedisTemplate valkey;
    static final Map<String, List<UUID>> phones = new java.util.concurrent.ConcurrentHashMap<>();
    static final ObjectMapper json = JsonMapper.builder().build();
    static final UUID SENDER = UUID.randomUUID();

    @BeforeAll
    static void start() throws Exception {
        String db = "inbox_" + UUID.randomUUID().toString().replace("-", "");
        TestInfra.freshComms(db);
        var ds = new DriverManagerDataSource(TestInfra.url(db), "comms_app", TestInfra.APP_PW);
        var app = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        su = new JdbcTemplate(new DriverManagerDataSource(TestInfra.url(db), TestInfra.PG.getUsername(), TestInfra.PG.getPassword()));
        var cf = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", TestInfra.redisPort));
        cf.afterPropertiesSet();
        valkey = new StringRedisTemplate(cf);
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        crypto = new FieldCrypto(key);
        var props = new CommsConsumerProperties("inb", new CommsConsumerProperties.Consumer(true, 50, 5, 60000));
        PatientDirectory dir = (t, phone) -> phones.getOrDefault(phone, List.of());
        inbox = new InboxService(app, tx, crypto, new StreamEventPublisher(valkey, props, json, CLOCK),
                new StaticListableBeanFactory(Map.of("dir", dir)).getBeanProvider(PatientDirectory.class), CLOCK);
    }

    static InboxService.Inbound text(String t, Instant at) {
        return new InboxService.Inbound("wamid." + UUID.randomUUID(), "text", t, null, null, null, at);
    }

    static String phone() {
        return "+9198" + (10000000 + (int) (Math.random() * 89999999));
    }

    @Test
    void newInboundIncrementsUnreadStoresEncryptedTextPublishesTheEventAndIgnoresAReplayedWamid() {
        UUID tenant = UUID.randomUUID();
        String p = phone();
        var m1 = text("secret symptom words", NOW.minusSeconds(120));
        var r1 = inbox.recordInbound(tenant, SENDER, p, m1);
        var r2 = inbox.recordInbound(tenant, SENDER, p, text("second", NOW.minusSeconds(60)));
        assertThat(r2.threadId()).as("same phone, same thread").isEqualTo(r1.threadId());
        assertThat(inbox.recordInbound(tenant, SENDER, p, m1).duplicate()).as("webhook redelivery").isTrue();
        var page = inbox.threads(tenant, "unread", null, 50);
        assertThat(page.items()).singleElement().satisfies(t -> {
            assertThat(t.unread()).isEqualTo(2);
            assertThat(t.lastMessagePreview()).isEqualTo("second");
            assertThat(t.waitingMinutes()).as("waiting since the first unanswered message").isEqualTo(2);
            assertThat(t.displayName()).as("phone masked").endsWith(p.substring(p.length() - 4)).doesNotContain(p.substring(0, 6));
        });
        byte[] stored = su.queryForObject("SELECT text_enc FROM inbound_messages WHERE wamid = ?", byte[].class, m1.wamid());
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("secret symptom");
        assertThat(crypto.decrypt(stored)).isEqualTo("secret symptom words");
        var ev = new ArrayList<String>();
        for (var rec : valkey.opsForStream().range("ev:inb:comms", org.springframework.data.domain.Range.unbounded())) {
            String ce = String.valueOf(rec.getValue().get("ce"));
            if (ce.contains(tenant.toString()) && ce.contains("comms.inbound.received.v1")) {
                ev.add(ce);
            }
        }
        assertThat(ev).as("one event per stored message, none for the replay").hasSize(2);
        assertThat(ev.get(0)).contains(r1.threadId().toString()).doesNotContain("secret symptom");
    }

    @Test
    void windowStatesAndTheClosingFilter() {
        UUID tenant = UUID.randomUUID();
        String closing = phone(), fresh = phone(), old = phone();
        inbox.recordInbound(tenant, SENDER, closing, text("a", NOW.minus(Duration.ofHours(23))));
        inbox.recordInbound(tenant, SENDER, fresh, text("b", NOW.minus(Duration.ofHours(10))));
        UUID oldThread = inbox.recordInbound(tenant, SENDER, old, text("c", NOW.minus(Duration.ofHours(25)))).threadId();
        var closingPage = inbox.threads(tenant, "closing", null, 50);
        assertThat(closingPage.items()).singleElement().satisfies(t -> {
            assertThat(t.window().state()).isEqualTo("open");
            assertThat(Duration.between(NOW, t.window().closesAt())).as("'Closes in 1 h'").isEqualTo(Duration.ofHours(1));
        });
        var all = inbox.threads(tenant, "all", null, 50).items();
        assertThat(all).extracting(t -> t.window().state()).containsExactlyInAnyOrder("open", "open", "closed");
        assertThatThrownBy(() -> inbox.requireFreeTextAllowed(tenant, oldThread)).isInstanceOfSatisfying(InboxException.class, e -> {
            assertThat(e.status().value()).isEqualTo(409);
            assertThat(e.code()).isEqualTo("SESSION_WINDOW_CLOSED");
        });
        inbox.requireFreeTextAllowed(tenant, closingPage.items().get(0).id()); // open: allowed
        // boundary: exactly 24 h after the last inbound is closed
        String edge = phone();
        UUID edgeThread = inbox.recordInbound(tenant, SENDER, edge, text("d", NOW.minus(Duration.ofHours(24)))).threadId();
        assertThatThrownBy(() -> inbox.requireFreeTextAllowed(tenant, edgeThread)).isInstanceOf(InboxException.class);
    }

    @Test
    void aPhoneMatchingAMotherAndTwoChildrenLinksToTheFamilyAndOneClickPicksThePatient() {
        UUID tenant = UUID.randomUUID();
        String p = phone(), solo = phone();
        UUID mother = UUID.randomUUID(), c1 = UUID.randomUUID(), c2 = UUID.randomUUID(), only = UUID.randomUUID();
        phones.put(p.replaceAll("[^0-9+]", ""), List.of(mother, c1, c2));
        phones.put(solo.replaceAll("[^0-9+]", ""), List.of(only));
        UUID famThread = inbox.recordInbound(tenant, SENDER, p, text("hi", NOW)).threadId();
        inbox.recordInbound(tenant, SENDER, solo, text("hi", NOW));
        var items = inbox.threads(tenant, "all", null, 50).items();
        var fam = items.stream().filter(t -> t.id().equals(famThread)).findFirst().orElseThrow();
        assertThat(fam.patientRef()).as("several matches: reception picks").isNull();
        assertThat(fam.familyPatientRefs()).containsExactlyInAnyOrder(mother, c1, c2);
        assertThat(items.stream().filter(t -> only.equals(t.patientRef())).count()).as("exactly one match links directly").isEqualTo(1);
        assertThatThrownBy(() -> inbox.pickPatient(tenant, famThread, UUID.randomUUID())).isInstanceOfSatisfying(InboxException.class,
                e -> assertThat(e.code()).isEqualTo("PATIENT_NOT_IN_FAMILY"));
        inbox.pickPatient(tenant, famThread, c2);
        assertThat(inbox.threads(tenant, "all", null, 50).items().stream().filter(t -> t.id().equals(famThread)).findFirst().orElseThrow().patientRef()).isEqualTo(c2);
        inbox.recordInbound(tenant, SENDER, p, text("again", NOW)); // a later message must not undo the pick
        assertThat(inbox.threads(tenant, "all", null, 50).items().stream().filter(t -> t.id().equals(famThread)).findFirst().orElseThrow().patientRef()).isEqualTo(c2);
    }

    @Test
    void oldestWaitingFirstFiltersCursorPagingReadAndReply() {
        UUID tenant = UUID.randomUUID();
        List<UUID> byAge = new ArrayList<>();
        for (int i = 5; i >= 1; i--) { // oldest first by construction
            byAge.add(inbox.recordInbound(tenant, SENDER, phone(), text("m" + i, NOW.minus(Duration.ofMinutes(i * 10L)))).threadId());
        }
        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            var page = inbox.threads(tenant, "needs_reply", cursor, 2);
            page.items().forEach(t -> seen.add(t.id()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);
        assertThat(seen).as("answer the oldest first, no repeats across pages").containsExactlyElementsOf(byAge);
        assertThat(pages).isEqualTo(3);
        assertThat(inbox.threads(tenant, "all", null, 1000).items()).as("limit is capped").hasSize(5);
        // read up to a message: partial read keeps the later one unread
        UUID t0 = byAge.get(0);
        inbox.recordInbound(tenant, SENDER, "+910000", text("x", NOW)); // other thread
        var first = su.queryForObject("SELECT id FROM inbound_messages WHERE thread_id = ?", UUID.class, t0);
        inbox.markRead(tenant, t0, first);
        assertThat(inbox.threads(tenant, "unread", null, 50).items()).extracting(InboxService.ThreadItem::id).doesNotContain(t0);
        assertThatThrownBy(() -> inbox.markRead(tenant, t0, UUID.randomUUID())).isInstanceOfSatisfying(InboxException.class, e -> assertThat(e.status().value()).isEqualTo(404));
        inbox.markReplied(tenant, t0, NOW);
        assertThat(inbox.threads(tenant, "needs_reply", null, 50).items()).extracting(InboxService.ThreadItem::id).doesNotContain(t0);
        assertThatThrownBy(() -> inbox.threads(tenant, "bogus", null, 10)).isInstanceOfSatisfying(InboxException.class, e -> assertThat(e.status().value()).isEqualTo(400));
        assertThatThrownBy(() -> inbox.threads(tenant, "all", "not-a-cursor", 10)).isInstanceOfSatisfying(InboxException.class, e -> assertThat(e.code()).isEqualTo("INVALID_CURSOR"));
    }

    @Test
    void threadMessagesShowBothDirectionsAndAreTenantIsolated() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        UUID thread = inbox.recordInbound(a, SENDER, phone(), text("hello clinic", NOW.minusSeconds(100))).threadId();
        su.update("""
                INSERT INTO outbound_messages (id, tenant_id, to_address_enc, to_hash, template_name, language, status, thread_id, created_at)
                VALUES (gen_random_uuid(), ?, '\\x01', '\\x02', 'appt_confirm', 'en', 'delivered', ?, ?)""", a, thread, java.sql.Timestamp.from(NOW.minusSeconds(50)));
        var page = inbox.messages(a, thread, null, 50);
        assertThat(page.items()).extracting(InboxService.MessageItem::direction).containsExactly("out", "in");
        assertThat(page.items().get(0).templateName()).isEqualTo("appt_confirm");
        assertThat(page.items().get(1).text()).isEqualTo("hello clinic");
        assertThat(inbox.messages(a, thread, null, 1).nextCursor()).isNotNull();
        assertThatThrownBy(() -> inbox.messages(b, thread, null, 50)).as("another tenant sees 404, not the thread").isInstanceOfSatisfying(InboxException.class,
                e -> assertThat(e.status().value()).isEqualTo(404));
        assertThat(inbox.threads(b, "all", null, 50).items()).isEmpty();
        assertThatThrownBy(() -> inbox.markRead(b, thread, UUID.randomUUID())).isInstanceOf(InboxException.class);
    }

    @Test
    void needsReplyListAt2000ThreadsIsUnder300msP95() {
        UUID tenant = UUID.randomUUID();
        byte[] enc = crypto.encrypt("+919800000000");
        su.update("""
                INSERT INTO inbox_threads (id, tenant_id, sender_account_id, phone_hash, phone_enc, last_inbound_at, unread_count, waiting_since)
                SELECT gen_random_uuid(), ?, ?, ('\\x' || lpad(to_hex(g), 8, '0'))::bytea, ?, ?::timestamptz - (g || ' minutes')::interval, 1, ?::timestamptz - (g || ' minutes')::interval
                FROM generate_series(1, 2000) g""", tenant, SENDER, enc, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        su.execute("ANALYZE inbox_threads");
        List<Long> ms = new ArrayList<>();
        for (int run = 0; run < 40; run++) {
            long t0 = System.nanoTime();
            String cursor = run % 2 == 0 ? null : inbox.threads(tenant, "needs_reply", null, 50).nextCursor();
            var page = inbox.threads(tenant, "needs_reply", cursor, 50);
            ms.add((System.nanoTime() - t0) / 1_000_000);
            assertThat(page.items()).hasSize(50);
        }
        ms.sort(null);
        long p95 = ms.get((int) Math.ceil(ms.size() * 0.95) - 1);
        System.out.println("COM-030 list p95 ms (two calls per sample incl. cursor page) = " + p95);
        assertThat(p95).isLessThan(300);
    }
}
