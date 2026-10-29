package com.nabd.edge;

import com.nabd.edge.FlowHandler.Conversation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** IDC-005: the WhatsApp consent step against the fake Core and Comms (the real WhatsApp sandbox is Needs external action). */
class ConsentFlowTest extends EdgeTestBase {

    @Autowired ConversationEngine engine;
    @Autowired ConversationStore store;
    @Autowired ConsentFlow flow;
    @Autowired ObjectMapper json;

    /** Starts the consent step the way the registration flow will: begin() then commit the outcome. */
    private Conversation start(UUID tenant, String contact, String lang) {
        engine.open(tenant, "whatsapp", contact, c -> flow.begin(c, lang), "seed-" + FakeUpstreams.id());
        return store.load(tenant, "whatsapp", contact);
    }

    private JsonNode lastSent() {
        return json.readTree(UP.sent.get(UP.sent.size() - 1));
    }

    /** The signed payload of the button with this title in the last message the Edge sent. */
    private String button(String title) {
        for (JsonNode b : lastSent().path("buttons")) {
            if (title.equals(b.path("title").asString())) {
                return b.path("id").asString();
            }
        }
        throw new AssertionError("no button titled " + title + " in " + UP.sent.get(UP.sent.size() - 1));
    }

    private String tap(UUID tenant, String contact, String title) {
        String id = FakeUpstreams.id();
        UP.inbound(id, contact, "", button(title));
        engine.process(tenant, id, "whatsapp", contact);
        return id;
    }

    /** The granted flag the relayed body carries for a purpose, or null when the purpose is absent. */
    private Boolean granted(String relayedBody, String purpose) {
        for (JsonNode d : json.readTree(relayedBody).path("decisions")) {
            if (purpose.equals(d.path("purpose").asString())) {
                return d.path("granted").asBoolean();
            }
        }
        return null;
    }

    private String state(UUID tenant) {
        return superuser().queryForObject("select state from conversations where tenant_id = ?::uuid", String.class, tenant.toString());
    }

    @Test
    void agreeThenUpdatesOnlyRelaysThreeDecisionsWithWamidAndNoticeVersion_inTwoTaps_marketingFalse() {
        UUID t = UUID.randomUUID();
        UP.noticeVersion = "v7";
        start(t, "hash-c1", "en");
        assertTrue(lastSent().path("text").asString().contains("https://notice.example.test/v1/public/notices/clinic/v7"));
        assertEquals(2, lastSent().path("buttons").size());

        long t0 = System.nanoTime();
        tap(t, "hash-c1", "I agree");                                  // tap 1
        assertEquals("CONSENT_UPDATES", state(t));
        assertTrue(UP.relayed.isEmpty(), "nothing is stored after the first tap");
        String wamid = tap(t, "hash-c1", "Updates only");              // tap 2
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 5000, "the whole consent step is quick on our side");

        assertEquals(1, UP.relayed.size());
        JsonNode r = json.readTree(UP.relayed.get(0));
        assertEquals("v7", r.path("noticeVersion").asString());
        assertEquals("en", r.path("language").asString());
        assertEquals(wamid, r.path("evidence").path("wamid").asString());
        assertEquals("hash-c1", r.path("phoneHash").asString());
        assertEquals(true, granted(UP.relayed.get(0), "data_processing"));
        assertEquals(true, granted(UP.relayed.get(0), "messaging"));
        assertEquals(false, granted(UP.relayed.get(0), "marketing"), "offers are off unless tapped");
        assertEquals("DONE", state(t));
        assertFalse(r.toString().toLowerCase().contains("name"), "no demographic field in the relay");
    }

    @Test
    void offersAreGrantedOnlyByTheExplicitOffersTap_noUpdatesMeansMessagingFalse() {
        UUID t = UUID.randomUUID();
        start(t, "hash-c2", "en");
        tap(t, "hash-c2", "I agree");
        tap(t, "hash-c2", "Updates and offers");
        assertEquals(true, granted(UP.relayed.get(0), "marketing"));

        UUID t2 = UUID.randomUUID();
        UP.relayed.clear();
        start(t2, "hash-c3", "en");
        tap(t2, "hash-c3", "I agree");
        tap(t2, "hash-c3", "No updates");
        assertEquals(false, granted(UP.relayed.get(0), "messaging"));
        assertEquals(false, granted(UP.relayed.get(0), "marketing"));
        assertEquals(true, granted(UP.relayed.get(0), "data_processing"));
    }

    @Test
    void noAtTheFirstTapSavesNothingAndOffersACallBackOnly() {
        UUID t = UUID.randomUUID();
        start(t, "hash-c4", "en");
        tap(t, "hash-c4", "No thanks");
        assertTrue(UP.relayed.isEmpty(), "nothing is sent to Core, so no patient record can be created");
        assertEquals(0, UP.coreCalls.get());
        assertEquals("DONE", state(t));
        JsonNode reply = lastSent();
        assertEquals(1, reply.path("buttons").size());
        assertEquals("Call me back", reply.path("buttons").get(0).path("title").asString());
        assertTrue(reply.path("text").asString().contains("not saved anything"));
        assertEquals("{}", superuser().queryForObject("select context::text from conversations where tenant_id = ?::uuid", String.class, t.toString()));
    }

    @Test
    void freeTextDuringTheStepAsksAgainAndStoresNothing() {
        UUID t = UUID.randomUUID();
        start(t, "hash-c5", "en");
        String id = FakeUpstreams.id();
        UP.inbound(id, "hash-c5", "yes please book me", null);
        engine.process(t, id, "whatsapp", "hash-c5");
        assertEquals("CONSENT_CARE", state(t));
        assertEquals(2, lastSent().path("buttons").size());
        assertTrue(UP.relayed.isEmpty());
    }

    @Test
    void theSameTapDeliveredTwiceRelaysOnce() {
        UUID t = UUID.randomUUID();
        start(t, "hash-c6", "en");
        tap(t, "hash-c6", "I agree");
        String id = FakeUpstreams.id();
        UP.inbound(id, "hash-c6", "", button("Updates only"));
        engine.process(t, id, "whatsapp", "hash-c6");
        engine.process(t, id, "whatsapp", "hash-c6");
        assertEquals(1, UP.relayed.size());
    }

    @Test
    void staleNoticeShowsTheCurrentNoticeAgainAndStoresNothing() {
        UUID t = UUID.randomUUID();
        start(t, "hash-c7", "en");
        tap(t, "hash-c7", "I agree");
        UP.consentStatus = 409;
        UP.noticeVersion = "v8";
        tap(t, "hash-c7", "Updates only");
        assertEquals("CONSENT_CARE", state(t));
        assertTrue(lastSent().path("text").asString().contains("/v8?lang="));
        assertEquals("v8", superuser().queryForObject("select context->>'noticeVersion' from conversations where tenant_id = ?::uuid", String.class, t.toString()));
    }

    @Test
    void coreOutageLosesNothing_theTapIsNotClaimedAndTheRetryStoresIt() {
        UUID t = UUID.randomUUID();
        start(t, "hash-c8", "en");
        tap(t, "hash-c8", "I agree");
        UP.consentStatus = 500;
        String id = FakeUpstreams.id();
        UP.inbound(id, "hash-c8", "", button("Updates only"));
        int sentBefore = UP.sent.size();
        assertThrows(RuntimeException.class, () -> engine.process(t, id, "whatsapp", "hash-c8"));
        assertEquals(sentBefore, UP.sent.size(), "the patient is not told it worked");
        assertEquals("CONSENT_UPDATES", state(t));
        UP.consentStatus = 201;
        engine.process(t, id, "whatsapp", "hash-c8"); // the event is redelivered
        assertEquals("DONE", state(t));
        assertEquals(2, UP.relayed.size(), "one failed attempt and one stored; Core is idempotent on the wamid");
    }

    @Test
    void everyLanguageHasShortButtonsAndNoPersonalData() {
        for (var e : ConsentCopy.all().entrySet()) {
            ConsentCopy.Copy c = e.getValue();
            for (String title : List.of(c.agree(), c.no(), c.updOnly(), c.updOffers(), c.updNone(), c.callback())) {
                assertTrue(title.codePointCount(0, title.length()) <= 20, e.getKey() + " button too long: " + title);
            }
            assertTrue(c.notice().contains("%s"), e.getKey());
            for (String text : List.of(c.notice(), c.updates(), c.declined(), c.done())) {
                assertFalse(text.matches("(?s).*\\d{6,}.*"), e.getKey() + " carries a number");
            }
        }
        assertEquals(Set4.LANGS, ConsentCopy.all().keySet());
        UUID t = UUID.randomUUID();
        start(t, "hash-c9", "kn");
        assertTrue(lastSent().path("buttons").get(0).path("title").asString().equals(ConsentCopy.of("kn").agree()));
        UUID t2 = UUID.randomUUID();
        start(t2, "hash-c10", "xx"); // unsupported falls back to English
        assertEquals("I agree", lastSent().path("buttons").get(0).path("title").asString());
        assertFalse(lastSent().path("text").asString().contains("hash-c10"), "the contact hash is not part of the message text");
    }

    private static final class Set4 {
        static final java.util.Set<String> LANGS = java.util.Set.of("en", "hi", "kn", "te");
    }
}
