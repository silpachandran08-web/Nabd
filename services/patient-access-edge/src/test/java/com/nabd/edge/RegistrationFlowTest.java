package com.nabd.edge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ACC-043: chat registration and "complete in chat" against the fake Core and Comms (a real WhatsApp number is Needs external action). */
class RegistrationFlowTest extends EdgeTestBase {

    @Autowired ConversationEngine engine;
    @Autowired ConversationStore store;
    @Autowired RegistrationFlow reg;
    @Autowired RegistrationFlow.Start start;
    @Autowired ObjectMapper json;

    /** "METHOD path body" of every Core call this test class cares about, in order. */
    final List<String> calls = new CopyOnWriteArrayList<>();
    final List<String> keys = new CopyOnWriteArrayList<>();
    volatile int createStatus = 201;
    volatile boolean pending = false;

    @BeforeEach
    void hooks() {
        calls.clear();
        keys.clear();
        createStatus = 201;
        pending = false;
        UP.noticeVersion = "v3";
        UP.consentStatus = 201;
        UP.coreHook = ex -> {
            String p = ex.getRequestURI().getPath();
            String m = ex.getRequestMethod();
            if (p.endsWith("/patients/consents")) {
                calls.add("CONSENT " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                FakeUpstreams.reply(ex, 201, "{\"consentIds\":[\"cid-1\"]}");
                return true;
            }
            if (p.contains("/patients/pending-completion/")) {
                FakeUpstreams.reply(ex, pending ? 200 : 404, pending ? "{\"patientRef\":\"pref-9\",\"firstName\":\"Asha\"}" : "{}");
                return true;
            }
            if (p.endsWith("/patients") && "POST".equals(m)) {
                keys.add(ex.getRequestHeaders().getFirst("Idempotency-Key"));
                calls.add("CREATE " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                FakeUpstreams.reply(ex, createStatus, createStatus == 201 ? "{\"patientRef\":\"pref-1\"}" : "{\"code\":\"POSSIBLE_DUPLICATE\"}");
                return true;
            }
            if (p.contains("/patients/pref-") && "PATCH".equals(m)) {
                calls.add("PATCH " + p.substring(p.lastIndexOf('/') + 1) + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                FakeUpstreams.reply(ex, 200, "{}");
                return true;
            }
            return false;
        };
        UP.commsHook = ex -> {
            String p = ex.getRequestURI().getPath();
            if (p.endsWith("/phone")) {
                FakeUpstreams.reply(ex, 200, "{\"phone\":\"+919800000001\"}");
                return true;
            }
            return false;
        };
    }

    @AfterEach
    void unhook() { // the fakes are shared with the other Edge tests
        UP.coreHook = null;
        UP.commsHook = null;
    }

    private JsonNode lastSent() {
        return json.readTree(UP.sent.get(UP.sent.size() - 1));
    }

    private String button(String title) {
        for (JsonNode b : lastSent().path("buttons")) {
            if (title.equals(b.path("title").asString())) {
                return b.path("id").asString();
            }
        }
        throw new AssertionError("no button titled " + title + " in " + UP.sent.get(UP.sent.size() - 1));
    }

    private String send(UUID t, String contact, String text, String payload) {
        String id = FakeUpstreams.id();
        UP.inbound(id, contact, text, payload);
        engine.process(t, id, "whatsapp", contact);
        return id;
    }

    private String state(UUID t) {
        return superuser().queryForObject("select state from conversations where tenant_id = ?::uuid", String.class, t.toString());
    }

    private String context(UUID t) {
        return superuser().queryForObject("select context::text from conversations where tenant_id = ?::uuid", String.class, t.toString());
    }

    /** Notice shown, both consent taps given, name and sex answered: the chat now waits for the date of birth. */
    private UUID untilDob(String contact) {
        UUID t = UUID.randomUUID();
        engine.open(t, "whatsapp", contact, c -> reg.enter(c, "en"), "seed-" + FakeUpstreams.id());
        assertTrue(lastSent().path("text").asString().contains("/v1/public/notices/clinic/v3"), "the notice comes first");
        assertTrue(calls.isEmpty());
        send(t, contact, "", button("I agree"));
        send(t, contact, "", button("Updates only"));
        assertEquals("REG_NAME", state(t));
        assertEquals(1, calls.size(), "only the consent so far, no patient");
        send(t, contact, "Asha Rao", null);
        assertEquals("REG_SEX", state(t));
        assertEquals(3, lastSent().path("buttons").size());
        send(t, contact, "", button("Female"));
        assertEquals("REG_DOB", state(t));
        return t;
    }

    @Test
    void unknownNumber_noticeThenConsentThenNameSexDob_createsThePatientAfterConsentAndMovesOnToBooking() {
        UUID t = untilDob("hash-r1");
        String wamid = send(t, "hash-r1", "25-03-1990", null);
        assertEquals(2, calls.size());
        assertTrue(calls.get(0).startsWith("CONSENT") && calls.get(1).startsWith("CREATE"), "consent is stored before the patient exists");
        JsonNode body = json.readTree(calls.get(1).substring(7));
        assertEquals("1990-03-25", body.path("dob").asString());
        assertEquals("Asha Rao", body.path("firstName").asString());
        assertEquals("female", body.path("sex").asString());
        assertEquals("cid-1", body.path("consentRecordId").asString());
        assertEquals("+919800000001", body.path("phone").asString());
        assertEquals("hash-r1", body.path("phoneHash").asString());
        assertTrue(body.path("phoneVerified").asBoolean());
        assertEquals("reg-" + wamid, keys.get(0));
        assertEquals("CHOOSING_DOCTOR", state(t));
        assertFalse(context(t).contains("1990"), "the date of birth is not kept in the Edge");
        assertTrue(context(t).contains("pref-1"));
    }

    @Test
    void ageInsteadOfDateOfBirthIsAccepted() {
        UUID t = untilDob("hash-r2");
        send(t, "hash-r2", "34", null);
        JsonNode body = json.readTree(calls.get(1).substring(7));
        assertEquals(34, body.path("age").asInt());
        assertTrue(body.path("dob").isMissingNode());
        assertEquals("CHOOSING_DOCTOR", state(t));
    }

    @Test
    void underEighteen_asksForAGuardian_noPatientNoBooking() {
        UUID t = untilDob("hash-r3");
        send(t, "hash-r3", "10-05-2015", null);
        assertEquals(1, calls.size(), "no create call");
        assertEquals("DONE", state(t));
        assertTrue(lastSent().path("text").asString().contains("parent or guardian"));
        assertEquals("Talk to clinic", lastSent().path("buttons").get(0).path("title").asString());
        send(t, "hash-r3", "34", null); // typing an adult age afterwards does not reopen the flow
        assertEquals(1, calls.size());
    }

    @Test
    void unreadableOrImpossibleDates_askAgainAndCreateNothing() {
        UUID t = untilDob("hash-r4");
        for (String bad : List.of("31-02-1990", "next week", "01-01-2999", "200")) {
            send(t, "hash-r4", bad, null);
            assertEquals("REG_DOB", state(t), bad);
        }
        assertEquals(1, calls.size());
    }

    @Test
    void decliningTheNotice_storesOnlyTheDeclinedDecision_createsNoPatient_explainsPhoneBooking_offersTalkToClinic() {
        UUID t = UUID.randomUUID();
        engine.open(t, "whatsapp", "hash-r5", c -> reg.enter(c, "en"), "seed-" + FakeUpstreams.id());
        send(t, "hash-r5", "", button("No thanks"));
        assertEquals(1, calls.size());
        JsonNode r = json.readTree(calls.get(0).substring(8));
        assertEquals(1, r.path("decisions").size());
        assertEquals("data_processing", r.path("decisions").get(0).path("purpose").asString());
        assertFalse(r.path("decisions").get(0).path("granted").asBoolean());
        assertEquals("DONE", state(t));
        assertTrue(lastSent().path("text").asString().contains("book by phone"));
        assertEquals("Talk to clinic", lastSent().path("buttons").get(0).path("title").asString());
    }

    @Test
    void likelyDuplicate_isNotCreatedAgain_andTheChatHandsOverToTheClinic() {
        UUID t = untilDob("hash-r6");
        createStatus = 409;
        send(t, "hash-r6", "25-03-1990", null);
        assertEquals("DONE", state(t));
        assertEquals("Talk to clinic", lastSent().path("buttons").get(0).path("title").asString());
        assertFalse(context(t).contains("pref-"));
    }

    @Test
    void completeInChat_asksAddressThenLanguage_andWritesBoth_withoutKeepingTheAddress() {
        pending = true;
        UUID t = UUID.randomUUID();
        engine.open(t, "whatsapp", "hash-r7", c -> start.handle(c, null), "seed-" + FakeUpstreams.id());
        assertEquals("COMPLETE_ADDRESS", state(t));
        assertTrue(lastSent().path("text").asString().contains("Asha"));
        send(t, "hash-r7", "12 Lake Road Bengaluru", null);
        assertEquals("COMPLETE_LANG", state(t));
        assertEquals("PATCH pref-9 {\"address\":\"12 Lake Road Bengaluru\"}", calls.get(0));
        assertFalse(context(t).contains("Lake Road"));
        send(t, "hash-r7", "", button("Hindi"));
        assertEquals("PATCH pref-9 {\"preferredLanguage\":\"hi\"}", calls.get(1));
        assertEquals("DONE", state(t));
    }

    @Test
    void nobodyPending_theFirstMessageStartsWithTheNotice() {
        UUID t = UUID.randomUUID();
        engine.open(t, "whatsapp", "hash-r8", c -> start.handle(c, null), "seed-" + FakeUpstreams.id());
        assertEquals("CONSENT_CARE", state(t));
        assertNull(superuser().queryForObject("select context->>'patientRef' from conversations where tenant_id = ?::uuid", String.class, t.toString()));
    }
}
