package com.nabd.edge;

import com.nabd.edge.FlowHandler.Conversation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** IDC-010: the add-a-family-member chat flow against the fake Core and Comms (real WhatsApp is Needs external action). */
class AddMemberFlowTest extends EdgeTestBase {

    @Autowired ConversationEngine engine;
    @Autowired ConversationStore store;
    @Autowired AddMemberFlow flow;
    @Autowired ObjectMapper json;

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    /** One fake Core endpoint per tenant: records the bodies and answers with the configured status. */
    private static final class MemberCore {
        final List<String> bodies = new CopyOnWriteArrayList<>();
        volatile int status = 201;
        volatile String proxyStatus = "guardian_consented";
        final AtomicInteger calls = new AtomicInteger();
    }

    private MemberCore core(UUID tenant) {
        MemberCore c = new MemberCore();
        UP.core.createContext("/internal/v1/tenants/" + tenant + "/patients/", ex -> {
            c.calls.incrementAndGet();
            c.bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = (c.status / 100 == 2 ? "{\"memberRef\":\"m-ref-1\",\"proxyStatus\":\"" + c.proxyStatus + "\"}" : "{}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(c.status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        return c;
    }

    private int taps;

    private void start(UUID tenant, String contact) {
        taps = 0;
        engine.open(tenant, "whatsapp", contact, c -> flow.begin(c, "cg-ref", "en"), "seed-" + FakeUpstreams.id());
    }

    private JsonNode lastSent() {
        return json.readTree(UP.sent.get(UP.sent.size() - 1));
    }

    private String lastText() {
        return lastSent().path("text").asString();
    }

    private void tap(UUID tenant, String contact, String title) {
        String payload = null;
        for (JsonNode b : lastSent().path("buttons")) {
            if (title.equals(b.path("title").asString())) {
                payload = b.path("id").asString();
            }
        }
        if (payload == null) {
            throw new AssertionError("no button titled " + title + " in " + UP.sent.get(UP.sent.size() - 1));
        }
        taps++;
        String id = FakeUpstreams.id();
        UP.inbound(id, contact, "", payload);
        engine.process(tenant, id, "whatsapp", contact);
    }

    private void say(UUID tenant, String contact, String text) {
        String id = FakeUpstreams.id();
        UP.inbound(id, contact, text, null);
        engine.process(tenant, id, "whatsapp", contact);
    }

    private String state(UUID tenant) {
        return superuser().queryForObject("select state from conversations where tenant_id = ?::uuid", String.class, tenant.toString());
    }

    private String code() {
        Matcher m = Pattern.compile("Your code is (\\d{6})").matcher(lastText());
        assertTrue(m.find(), lastText());
        return m.group(1);
    }

    @Test
    void childOfSixIsAddedInTwoTapsWithTheGuardiansOtpAsEvidence() {
        UUID t = UUID.randomUUID();
        MemberCore core = core(t);
        UP.noticeVersion = "v3";
        start(t, "hash-m1");
        tap(t, "hash-m1", "My child");
        say(t, "hash-m1", "Aarav Kumar");
        say(t, "hash-m1", LocalDate.now().minusYears(6).minusDays(10).format(DMY));
        assertEquals("MEMBER_CONSENT", state(t));
        assertTrue(lastText().contains("https://notice.example.test/v1/public/notices/clinic/v3"), "the guardian sees the current notice");
        assertEquals(0, core.calls.get(), "nothing reaches Core before the guardian's code");
        tap(t, "hash-m1", "I agree");
        assertEquals("MEMBER_OTP", state(t));
        String right = code();
        String wrong = right.equals("000000") ? "000001" : "000000";
        say(t, "hash-m1", wrong);
        assertEquals("MEMBER_OTP", state(t));
        assertTrue(lastText().contains("not right"));
        assertEquals(0, core.calls.get());
        say(t, "hash-m1", right);
        assertEquals("DONE", state(t));
        assertTrue(taps <= 6 && taps == 2, "taps used: " + taps);
        assertEquals(1, core.calls.get());
        JsonNode b = json.readTree(core.bodies.get(0));
        assertEquals("child", b.path("relation").asString());
        assertEquals("Aarav Kumar", b.path("name").asString());
        assertEquals(LocalDate.now().minusYears(6).minusDays(10).toString(), b.path("dob").asString());
        assertEquals("v3", b.path("guardianConsent").path("noticeVersion").asString());
        assertTrue(b.path("guardianConsent").path("otpChallengeId").asString().length() >= 8);
        assertFalse(b.has("phone"));
        assertTrue(lastText().startsWith("Aarav is added"), lastText());
        assertEquals("{}", superuser().queryForObject("select context::text from conversations where tenant_id = ?::uuid", String.class, t.toString()), "typed answers are not kept");
        assertFalse(UP.sent.stream().anyMatch(s -> json.readTree(s).path("text").asString().contains("hash-m1")), "no message text carries the contact hash");
    }

    @Test
    void adultWithOwnPhoneSendsTheNumberAndNoGuardianConsent() {
        UUID t = UUID.randomUUID();
        MemberCore core = core(t);
        core.proxyStatus = "awaiting_consent";
        start(t, "hash-m2");
        tap(t, "hash-m2", "Someone else");
        say(t, "hash-m2", "Ravi Menon");
        say(t, "hash-m2", "07/03/1985");
        assertEquals("MEMBER_PHONE_CHOICE", state(t));
        tap(t, "hash-m2", "Has own phone");
        say(t, "hash-m2", "98765 43210");
        assertEquals("MEMBER_PHONE", state(t), "a number without the country code is asked again");
        say(t, "hash-m2", "+91 98765 43210");
        tap(t, "hash-m2", "Yes, add them");
        assertEquals("DONE", state(t));
        assertEquals(3, taps);
        JsonNode b = json.readTree(core.bodies.get(0));
        assertEquals("+919876543210", b.path("phone").asString());
        assertEquals("1985-03-07", b.path("dob").asString());
        assertFalse(b.has("guardianConsent"));
        assertTrue(lastText().contains("waiting for their own consent"), lastText());
    }

    @Test
    void adultWithoutPhoneIsAddedForBookingAndTheDeskConfirmsLater() {
        UUID t = UUID.randomUUID();
        MemberCore core = core(t);
        core.proxyStatus = "pending_desk_confirmation";
        start(t, "hash-m3");
        tap(t, "hash-m3", "My parent");
        say(t, "hash-m3", "Lakshmi Devi");
        say(t, "hash-m3", "01-01-1950");
        tap(t, "hash-m3", "No phone");
        tap(t, "hash-m3", "Yes, add them");
        assertEquals("DONE", state(t));
        assertFalse(json.readTree(core.bodies.get(0)).has("phone"));
        assertEquals("parent", json.readTree(core.bodies.get(0)).path("relation").asString());
        assertTrue(lastText().contains("confirm their consent at the next visit"), lastText());
        assertTrue(taps <= 6);
    }

    @Test
    void badInputIsAskedAgain_cancelSavesNothing_threeWrongCodesEndTheFlow() {
        UUID t = UUID.randomUUID();
        MemberCore core = core(t);
        start(t, "hash-m4");
        say(t, "hash-m4", "hello");                                  // a typed word where a tap is expected
        assertEquals("MEMBER_RELATION", state(t));
        tap(t, "hash-m4", "My child");
        say(t, "hash-m4", "R2D2");
        assertEquals("MEMBER_NAME", state(t));
        say(t, "hash-m4", "Zed Kumar");
        say(t, "hash-m4", "31-02-2018");
        assertEquals("MEMBER_DOB", state(t));
        say(t, "hash-m4", LocalDate.now().plusDays(1).format(DMY));
        assertEquals("MEMBER_DOB", state(t), "a future date of birth is refused");
        say(t, "hash-m4", "01-01-1800");
        assertEquals("MEMBER_DOB", state(t), "older than 120 years is refused");
        say(t, "hash-m4", LocalDate.now().minusYears(17).minusDays(1).format(DMY));
        assertEquals("MEMBER_CONSENT", state(t), "17 years and 364 days is still a minor: guardian consent");
        tap(t, "hash-m4", "I agree");
        say(t, "hash-m4", "x");
        say(t, "hash-m4", "y");
        say(t, "hash-m4", "z");
        assertEquals("DONE", state(t));
        assertEquals(0, core.calls.get());
        assertTrue(lastText().contains("nothing was saved"));

        UUID t2 = UUID.randomUUID();
        MemberCore core2 = core(t2);
        start(t2, "hash-m5");
        tap(t2, "hash-m5", "My child");
        say(t2, "hash-m5", "Zed Kumar");
        say(t2, "hash-m5", "01-01-2020");
        tap(t2, "hash-m5", "Cancel");
        assertEquals("DONE", state(t2));
        assertEquals(0, core2.calls.get());
    }

    @Test
    void eighteenTodayIsAnAdultAndTheStaleNoticeShowsTheConsentAgain_coreOutageLosesNothing() {
        UUID t = UUID.randomUUID();
        MemberCore core = core(t);
        start(t, "hash-m6");
        tap(t, "hash-m6", "My child");
        say(t, "hash-m6", "Neel Kumar");
        say(t, "hash-m6", LocalDate.now().minusYears(18).format(DMY));
        assertEquals("MEMBER_PHONE_CHOICE", state(t), "exactly 18 is an adult");

        UUID t2 = UUID.randomUUID();
        MemberCore core2 = core(t2);
        start(t2, "hash-m7");
        tap(t2, "hash-m7", "My child");
        say(t2, "hash-m7", "Mina Kumar");
        say(t2, "hash-m7", "01-01-2019");
        tap(t2, "hash-m7", "I agree");
        core2.status = 409;
        UP.noticeVersion = "v9";
        say(t2, "hash-m7", code());
        assertEquals("MEMBER_CONSENT", state(t2), "stale notice: the guardian is asked again against the current one");
        assertTrue(lastText().contains("/v9?lang="));
        core2.status = 500;
        tap(t2, "hash-m7", "I agree");
        String id = FakeUpstreams.id();
        String c = code();
        UP.inbound(id, "hash-m7", c, null);
        int before = UP.sent.size();
        assertThrows(RuntimeException.class, () -> engine.process(t2, id, "whatsapp", "hash-m7"));
        assertEquals(before, UP.sent.size(), "the guardian is not told it worked");
        assertEquals("MEMBER_OTP", state(t2));
        core2.status = 201;
        engine.process(t2, id, "whatsapp", "hash-m7");
        assertEquals("DONE", state(t2));
    }
}
