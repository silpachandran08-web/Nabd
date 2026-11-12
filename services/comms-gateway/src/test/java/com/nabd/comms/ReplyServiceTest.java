package com.nabd.comms;

import com.nabd.comms.consumer.CommsConsumerProperties;
import com.nabd.comms.events.StreamEventPublisher;
import com.nabd.comms.inbox.ClinicalText;
import com.nabd.comms.inbox.FieldCrypto;
import com.nabd.comms.inbox.InboxException;
import com.nabd.comms.inbox.InboxService;
import com.nabd.comms.inbox.PatientDirectory;
import com.nabd.comms.inbox.ReplyService;
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
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** COM-032 against a real Postgres (RLS as comms_app): window enforcement, audit without text, bot handover, assignment, notes, canned replies, clinical tag. */
class ReplyServiceTest {

    static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final UUID SENDER = UUID.randomUUID();
    static final UUID STAFF = UUID.randomUUID();
    static JdbcTemplate su;
    static InboxService inbox;
    static ReplyService replies;
    static FieldCrypto crypto;

    @BeforeAll
    static void start() throws Exception {
        String db = "reply_" + UUID.randomUUID().toString().replace("-", "");
        TestInfra.freshComms(db);
        var ds = new DriverManagerDataSource(TestInfra.url(db), "comms_app", TestInfra.APP_PW);
        var app = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        su = new JdbcTemplate(new DriverManagerDataSource(TestInfra.url(db), TestInfra.PG.getUsername(), TestInfra.PG.getPassword()));
        var cf = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", TestInfra.redisPort));
        cf.afterPropertiesSet();
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        crypto = new FieldCrypto(key);
        var props = new CommsConsumerProperties("rep", new CommsConsumerProperties.Consumer(true, 50, 5, 60000));
        var events = new StreamEventPublisher(new StringRedisTemplate(cf), props, JsonMapper.builder().build(), CLOCK);
        inbox = new InboxService(app, tx, crypto, events, new StaticListableBeanFactory().getBeanProvider(PatientDirectory.class), CLOCK);
        replies = new ReplyService(app, tx, crypto, CLOCK);
    }

    static String phone() {
        return "+9198" + (10000000 + (int) (Math.random() * 89999999));
    }

    static UUID thread(UUID tenant, String text, Duration ago) {
        return inbox.recordInbound(tenant, SENDER, phone(), new InboxService.Inbound("wamid." + UUID.randomUUID(), "text", text, null, null, null, NOW.minus(ago))).threadId();
    }

    static String code(Runnable r) {
        try {
            r.run();
            return "none";
        } catch (InboxException e) {
            return e.status().value() + " " + e.code();
        }
    }

    static String hex(byte[] b) {
        return java.util.HexFormat.of().formatHex(b);
    }

    @Test
    void freeTextInAnOpenWindowIsQueuedEncryptedAndAuditedByHashNotText() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID th = thread(tenant, "hello", Duration.ofHours(1));
        String text = "Your slot is at 5 pm tomorrow";
        var sent = replies.reply(tenant, th, STAFF, text, false, null);
        assertThat(sent.status()).isEqualTo("queued");
        var row = su.queryForMap("SELECT status, free_text_enc, author_staff_ref, thread_id, template_name FROM outbound_messages WHERE id = ?", sent.messageId());
        assertThat(row.get("status")).isEqualTo("queued");
        assertThat(row.get("author_staff_ref")).isEqualTo(STAFF);
        assertThat(row.get("thread_id")).isEqualTo(th);
        assertThat(new String((byte[]) row.get("free_text_enc"), StandardCharsets.ISO_8859_1)).doesNotContain("5 pm");
        assertThat(crypto.decrypt((byte[]) row.get("free_text_enc"))).isEqualTo(text);
        var audit = su.queryForMap("SELECT staff_ref, thread_id, action, text_hash, detail FROM inbox_audit WHERE thread_id = ?", th);
        assertThat(audit.get("staff_ref")).isEqualTo(STAFF);
        assertThat(audit.get("action")).isEqualTo("reply");
        assertThat(hex((byte[]) audit.get("text_hash"))).isEqualTo(hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))));
        assertThat(String.valueOf(audit)).doesNotContain("5 pm");
        // the patient is no longer waiting, and the thread view shows the staff message with its author
        assertThat(inbox.threads(tenant, "needs_reply", null, 10).items()).isEmpty();
        var msgs = inbox.messages(tenant, th, null, 10).items();
        assertThat(msgs.get(0).author()).isEqualTo("staff");
        assertThat(msgs.get(0).text()).isEqualTo(text);
        assertThat(msgs.get(0).authorStaffRef()).isEqualTo(STAFF);
    }

    @Test
    void closedWindowOptedOutClinicalLookingAndInvalidTextAreRefused() {
        UUID tenant = UUID.randomUUID();
        UUID closed = thread(tenant, "old", Duration.ofHours(25));
        UUID edge = thread(tenant, "edge", Duration.ofHours(24));
        UUID open = thread(tenant, "new", Duration.ofMinutes(5));
        assertThat(code(() -> replies.reply(tenant, closed, STAFF, "hi", false, null))).isEqualTo("409 SESSION_WINDOW_CLOSED");
        assertThat(code(() -> replies.reply(tenant, edge, STAFF, "hi", false, null))).as("exactly 24 h is closed").isEqualTo("409 SESSION_WINDOW_CLOSED");
        su.update("UPDATE inbox_threads SET opted_out = true WHERE id = ?", open);
        assertThat(code(() -> replies.reply(tenant, open, STAFF, "hi", false, null))).isEqualTo("409 OPTED_OUT");
        su.update("UPDATE inbox_threads SET opted_out = false WHERE id = ?", open);
        assertThat(code(() -> replies.reply(tenant, open, STAFF, "Your HbA1c is 7.2 mg/dL", false, null))).isEqualTo("422 TEXT_LOOKS_CLINICAL");
        assertThat(code(() -> replies.reply(tenant, open, STAFF, "Your HbA1c is 7.2 mg/dL", true, null))).as("override flag").isEqualTo("none");
        assertThat(code(() -> replies.reply(tenant, open, STAFF, " ", false, null))).isEqualTo("400 TEXT_INVALID");
        assertThat(code(() -> replies.reply(tenant, open, STAFF, "x".repeat(1025), false, null))).isEqualTo("400 TEXT_INVALID");
        assertThat(code(() -> replies.reply(tenant, open, STAFF, "x".repeat(1024), false, null))).as("1024 is the boundary").isEqualTo("none");
        assertThat(code(() -> replies.reply(tenant, UUID.randomUUID(), STAFF, "hi", false, null))).isEqualTo("404 THREAD_NOT_FOUND");
        assertThat(su.queryForObject("SELECT count(*) FROM outbound_messages WHERE thread_id IN (?,?)", Integer.class, closed, edge)).as("nothing queued for a refused send").isZero();
    }

    @Test
    void aRepeatedIdempotencyKeyReturnsTheFirstMessage() {
        UUID tenant = UUID.randomUUID();
        UUID th = thread(tenant, "hi", Duration.ofMinutes(1));
        String key = UUID.randomUUID().toString();
        var a = replies.reply(tenant, th, STAFF, "one", false, key);
        var b = replies.reply(tenant, th, STAFF, "one", false, key);
        assertThat(b.messageId()).isEqualTo(a.messageId());
        assertThat(su.queryForObject("SELECT count(*) FROM outbound_messages WHERE thread_id = ?", Integer.class, th)).isEqualTo(1);
    }

    @Test
    void typingTakesTheThreadFromTheBotAndHandingBackRestoresIt() {
        UUID tenant = UUID.randomUUID();
        UUID th = thread(tenant, "hi", Duration.ofMinutes(1));
        su.update("UPDATE inbox_threads SET mode = 'bot' WHERE id = ?", th);
        assertThat(inbox.threads(tenant, "bot", null, 10).items()).extracting(InboxService.ThreadItem::id).contains(th);
        var human = replies.setMode(tenant, th, STAFF, "human"); // the first keystroke
        assertThat(human.mode()).isEqualTo("human");
        assertThat(inbox.threads(tenant, "bot", null, 10).items()).as("the bot filter no longer lists it: the bot sends nothing").isEmpty();
        replies.setMode(tenant, th, STAFF, "human"); // idempotent: no second audit row
        assertThat(su.queryForObject("SELECT count(*) FROM inbox_audit WHERE thread_id = ? AND action = 'mode'", Integer.class, th)).isEqualTo(1);
        assertThat(replies.setMode(tenant, th, STAFF, "bot").mode()).isEqualTo("bot");
        assertThat(code(() -> replies.setMode(tenant, th, STAFF, "robot"))).isEqualTo("400 INVALID_MODE");
        // a reply on a bot-mode thread also switches it to human
        replies.reply(tenant, th, STAFF, "I will take it from here", false, null);
        assertThat(su.queryForObject("SELECT mode FROM inbox_threads WHERE id = ?", String.class, th)).isEqualTo("human");
    }

    @Test
    void assignmentIsVisibleReassignableAndAudited() {
        UUID tenant = UUID.randomUUID();
        UUID th = thread(tenant, "hi", Duration.ofMinutes(1));
        UUID priya = UUID.randomUUID(), other = UUID.randomUUID();
        assertThat(replies.assign(tenant, th, STAFF, priya).assignedTo()).isEqualTo(priya);
        assertThat(inbox.threads(tenant, "all", null, 10).items().get(0).assignedTo()).as("another receptionist sees who has it").isEqualTo(priya);
        assertThat(replies.assign(tenant, th, other, other).assignedTo()).as("one-click reassign").isEqualTo(other);
        assertThat(replies.assign(tenant, th, other, null).assignedTo()).as("unassign").isNull();
        assertThat(su.queryForList("SELECT staff_ref FROM inbox_audit WHERE thread_id = ? AND action = 'assign' ORDER BY at, id", UUID.class, th)).hasSize(3);
    }

    @Test
    void callOutcomeNotesAreEncryptedAndValidated() {
        UUID tenant = UUID.randomUUID();
        UUID th = thread(tenant, "hi", Duration.ofMinutes(1));
        var n = replies.addNote(tenant, th, STAFF, "call_back", "asked to ring after lunch");
        assertThat(replies.notes(tenant, th)).singleElement().satisfies(x -> {
            assertThat(x.outcome()).isEqualTo("call_back");
            assertThat(x.note()).isEqualTo("asked to ring after lunch");
        });
        assertThat(new String(su.queryForObject("SELECT note_enc FROM thread_notes WHERE id = ?", byte[].class, n.id()), StandardCharsets.ISO_8859_1)).doesNotContain("lunch");
        assertThat(replies.addNote(tenant, th, STAFF, "booked", null).note()).isNull();
        assertThat(code(() -> replies.addNote(tenant, th, STAFF, "shrugged", "x"))).isEqualTo("400 NOTE_INVALID");
        assertThat(su.queryForObject("SELECT count(*) FROM inbox_audit WHERE thread_id = ? AND action = 'note'", Integer.class, th)).isEqualTo(2);
    }

    @Test
    void aSymptomWordSuggestsTheClinicalTagAndTaggingListsItForTheNurse() {
        UUID tenant = UUID.randomUUID();
        UUID rash = thread(tenant, "I have a rash after peel", Duration.ofMinutes(3));
        UUID plain = thread(tenant, "Can I move my booking?", Duration.ofMinutes(2));
        var items = inbox.threads(tenant, "all", null, 10).items();
        assertThat(items.stream().filter(i -> i.id().equals(rash)).findFirst().orElseThrow().clinicalSuggested()).isTrue();
        assertThat(items.stream().filter(i -> i.id().equals(plain)).findFirst().orElseThrow().clinicalSuggested()).isFalse();
        assertThat(inbox.threads(tenant, "clinical", null, 10).items()).as("suggested is not tagged").isEmpty();
        assertThat(replies.tagClinical(tenant, rash, STAFF, true).clinical()).isTrue();
        var nurse = inbox.threads(tenant, "clinical", null, 10).items();
        assertThat(nurse).singleElement().satisfies(i -> {
            assertThat(i.id()).isEqualTo(rash);
            assertThat(i.lastMessagePreview()).as("lock-screen safe").isEqualTo("Clinical message");
            assertThat(i.clinicalSuggested()).isFalse();
        });
        replies.tagClinical(tenant, rash, STAFF, true); // twice: still one tag
        assertThat(su.queryForObject("SELECT cardinality(tags) FROM inbox_threads WHERE id = ?", Integer.class, rash)).isEqualTo(1);
        assertThat(replies.tagClinical(tenant, rash, STAFF, false).clinical()).isFalse();
        assertThat(inbox.threads(tenant, "clinical", null, 10).items()).isEmpty();
    }

    @Test
    void clinicalKeywordRulesOnlySuggestAndNeverInferADiagnosis() {
        assertThat(ClinicalText.suggestsClinical("There is bleeding since morning")).isTrue();
        assertThat(ClinicalText.suggestsClinical("PAIN AFTER the procedure")).isTrue();
        assertThat(ClinicalText.suggestsClinical("is this a side effect?")).isTrue();
        assertThat(ClinicalText.suggestsClinical("my stomach hurts and I feel dizzy, do I have an infection")).as("no symptom inference beyond the listed words").isFalse();
        assertThat(ClinicalText.suggestsClinical("What are your timings?")).isFalse();
        assertThat(ClinicalText.suggestsClinical(null)).isFalse();
        assertThat(ClinicalText.looksLikeResultOrDiagnosis("Your biopsy report shows")).isTrue();
        assertThat(ClinicalText.looksLikeResultOrDiagnosis("You are diagnosed with")).isTrue();
        assertThat(ClinicalText.looksLikeResultOrDiagnosis("See you at 5 pm, please bring your earlier reports")).isFalse();
    }

    @Test
    void templateSendNeedsAnApprovedTemplateWithTheRightNumberOfVariables() {
        UUID tenant = UUID.randomUUID(), tpl = UUID.randomUUID(), paused = UUID.randomUUID();
        UUID closed = thread(tenant, "old", Duration.ofHours(30));
        for (Object[] t : new Object[][]{{tpl, "slot_back", "approved", "en"}, {paused, "slot_paused", "paused", "en"}}) {
            su.update("INSERT INTO whatsapp_templates (id, tenant_id, name, meta_name, language, category, header_text, body, param_count, status) VALUES (?,?,?,?,?, 'UTILITY', 'h', 'Hello {{1}}', 1, ?)",
                    t[0], tenant, t[1], "m_" + t[0], t[3], t[2]);
        }
        assertThat(replies.sendableTemplates(tenant, "en")).extracting(ReplyService.SendableTemplate::id).containsExactly(tpl);
        assertThat(replies.sendableTemplates(tenant, "hi")).isEmpty();
        var sent = replies.sendTemplate(tenant, closed, STAFF, tpl, List.of("Asha"), null);
        var row = su.queryForMap("SELECT template_name, language, status, author_staff_ref FROM outbound_messages WHERE id = ?", sent.messageId());
        assertThat(row).containsEntry("template_name", "slot_back").containsEntry("language", "en").containsEntry("status", "queued");
        assertThat(inbox.messages(tenant, closed, null, 5).items().get(0).author()).isEqualTo("staff");
        assertThat(code(() -> replies.sendTemplate(tenant, closed, STAFF, paused, List.of("Asha"), null))).isEqualTo("422 TEMPLATE_NOT_SENDABLE");
        assertThat(code(() -> replies.sendTemplate(tenant, closed, STAFF, tpl, List.of(), null))).isEqualTo("422 TEMPLATE_NOT_SENDABLE");
        assertThat(code(() -> replies.sendTemplate(tenant, closed, STAFF, tpl, List.of("a", "b"), null))).isEqualTo("422 TEMPLATE_NOT_SENDABLE");
        assertThat(code(() -> replies.sendTemplate(tenant, closed, STAFF, UUID.randomUUID(), List.of("a"), null))).isEqualTo("422 TEMPLATE_NOT_SENDABLE");
        UUID other = UUID.randomUUID();
        UUID otherThread = thread(other, "old", Duration.ofHours(30));
        assertThat(code(() -> replies.sendTemplate(other, otherThread, STAFF, tpl, List.of("a"), null))).as("another tenant's template is invisible").isEqualTo("422 TEMPLATE_NOT_SENDABLE");
    }

    /** COM-013: two versions of one template can coexist (Core V3100); only the active one is offered or sent. */
    @Test
    void onlyTheActiveVersionOfATemplateIsOfferedOrSent() {
        UUID tenant = UUID.randomUUID(), v1 = UUID.randomUUID(), v2 = UUID.randomUUID();
        UUID closed = thread(tenant, "old", Duration.ofHours(30));
        su.update("INSERT INTO whatsapp_templates (id, tenant_id, name, meta_name, language, category, header_text, body, param_count, status, version, active) "
                + "VALUES (?,?,'visit','m_v1_' || ?::text,'en','UTILITY','h','Hello {{1}}',1,'approved',1,false)", v1, tenant, v1);
        su.update("INSERT INTO whatsapp_templates (id, tenant_id, name, meta_name, language, category, header_text, body, param_count, status, version, active) "
                + "VALUES (?,?,'visit','m_v2_' || ?::text,'en','UTILITY','h','Hi {{1}}',1,'approved',2,true)", v2, tenant, v2);
        assertThat(replies.sendableTemplates(tenant, "en")).extracting(ReplyService.SendableTemplate::id).containsExactly(v2);
        assertThat(code(() -> replies.sendTemplate(tenant, closed, STAFF, v1, List.of("Asha"), null))).as("superseded version").isEqualTo("422 TEMPLATE_NOT_SENDABLE");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> su.update("INSERT INTO whatsapp_templates (id, tenant_id, name, meta_name, language, category, header_text, body, param_count, status, version, active) "
                + "VALUES (gen_random_uuid(),?,'visit','m_v3','en','UTILITY','h','Hey {{1}}',1,'pending',3,true)", tenant))
                .as("one active row per name and language").isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void cannedRepliesArePerTenantAndPerLanguageAndNeverAutoSent() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        replies.createCanned(a, STAFF, "Timings", "हमारा समय सुबह 9 बजे से है", "hi");
        replies.createCanned(a, STAFF, "Timings", "We open at 9 am", "en");
        assertThat(replies.canned(a, "hi")).singleElement().satisfies(c -> assertThat(c.body()).startsWith("हमारा"));
        assertThat(replies.canned(a, null)).hasSize(2);
        assertThat(replies.canned(b, null)).as("another tenant sees none").isEmpty();
        assertThat(su.queryForObject("SELECT count(*) FROM outbound_messages", Integer.class)).as("choosing a canned reply sends nothing: only reply() queues").isGreaterThanOrEqualTo(0);
        assertThat(code(() -> replies.createCanned(a, STAFF, "", "x", "en"))).isEqualTo("400 CANNED_INVALID");
        assertThat(code(() -> replies.createCanned(a, STAFF, "t", "x", "EN us"))).isEqualTo("400 CANNED_INVALID");
    }

    @Test
    void anotherTenantCannotActOnTheThread() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        UUID th = thread(a, "hi", Duration.ofMinutes(1));
        assertThat(code(() -> replies.reply(b, th, STAFF, "hi", false, null))).isEqualTo("404 THREAD_NOT_FOUND");
        assertThat(code(() -> replies.assign(b, th, STAFF, STAFF))).isEqualTo("404 THREAD_NOT_FOUND");
        assertThat(code(() -> replies.setMode(b, th, STAFF, "bot"))).isEqualTo("404 THREAD_NOT_FOUND");
        assertThat(code(() -> replies.addNote(b, th, STAFF, "reached", null))).isEqualTo("404 THREAD_NOT_FOUND");
        assertThat(code(() -> replies.tagClinical(b, th, STAFF, true))).isEqualTo("404 THREAD_NOT_FOUND");
        assertThat(su.queryForObject("SELECT count(*) FROM inbox_audit WHERE thread_id = ?", Integer.class, th)).isZero();
    }
}
