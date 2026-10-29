package com.nabd.edge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ACC-033: the WhatsApp check-in flow against the fake Core and Comms (the real WhatsApp sandbox is Needs external action). */
class CheckInFlowTest extends EdgeTestBase {

    @Autowired ConversationEngine engine;
    @Autowired ButtonTokens tokens;
    @Autowired ObjectMapper json;

    private static String cand(String ref, String name, String appt, boolean done) {
        return "{\"patientRef\":\"" + ref + "\",\"firstName\":\"" + name + "\",\"appointmentId\":\"" + appt + "\",\"checkedIn\":" + done + "}";
    }

    private static void context(boolean geo, String... candidates) {
        UP.checkInContext = "{\"facilityCode\":\"ABCD2345\",\"geofenceRequired\":" + geo + ",\"walkinAllowed\":false,\"candidates\":[" + String.join(",", candidates) + "]}";
    }

    private String text(UUID tenant, String contact, String body) {
        String id = FakeUpstreams.id();
        UP.inbound(id, contact, body, null);
        engine.process(tenant, id, "whatsapp", contact);
        return id;
    }

    private String lastText() {
        return json.readTree(UP.sent.get(UP.sent.size() - 1)).path("text").asString();
    }

    private String button(String title) {
        for (JsonNode b : json.readTree(UP.sent.get(UP.sent.size() - 1)).path("buttons")) {
            if (title.equals(b.path("title").asString())) return b.path("id").asString();
        }
        throw new AssertionError("no button " + title);
    }

    private void tap(UUID tenant, String contact, String title) {
        String id = FakeUpstreams.id();
        UP.inbound(id, contact, "", button(title));
        engine.process(tenant, id, "whatsapp", contact);
    }

    private String state(UUID tenant) {
        return superuser().queryForObject("select state from conversations where tenant_id = ?::uuid", String.class, tenant.toString());
    }

    @Test
    void theDoorQrLinkChecksTheSinglePatientInWithATokenAndPosition() {
        UUID t = UUID.randomUUID();
        String appt = UUID.randomUUID().toString();
        context(false, cand("ref-a", "Asha", appt, false));
        long t0 = System.nanoTime();
        text(t, "hash-ci1", "CHECKIN abcd2345"); // the prefilled text, any case
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 15_000);
        assertEquals(1, UP.checkIns.size());
        JsonNode sent = json.readTree(UP.checkIns.get(0).substring(UP.checkIns.get(0).indexOf(' ') + 1));
        assertEquals("ref-a", sent.path("patientRef").asString());
        assertEquals(appt, sent.path("appointmentId").asString());
        assertEquals("qr", sent.path("channel").asString());
        assertFalse(sent.has("location"));
        assertTrue(lastText().contains("Your token is 7") && lastText().contains("number 2"), lastText());
        assertEquals("DONE", state(t));
    }

    @Test
    void aWrongFacilityCodeOrNoAppointmentGoesToTheDeskMessageAndCallsCoreNoFurther() {
        UUID t = UUID.randomUUID();
        context(false, cand("ref-a", "Asha", UUID.randomUUID().toString(), false));
        text(t, "hash-ci2", "CHECKIN ZZZZ9999");
        assertTrue(lastText().contains("not for this clinic"));
        context(false);
        text(t, "hash-ci2", "CHECKIN ABCD2345");
        assertTrue(lastText().contains("front desk"));
        assertTrue(UP.checkIns.isEmpty());
    }

    @Test
    void anAlreadyCheckedInPatientIsToldTheExistingToken() {
        UUID t = UUID.randomUUID();
        context(false, cand("ref-a", "Asha", UUID.randomUUID().toString(), true));
        UP.checkInReplies.add(new Object[]{409, "{\"code\":\"ALREADY_CHECKED_IN\",\"token\":3,\"tokenNumber\":3}"});
        text(t, "hash-ci3", "CHECKIN ABCD2345");
        assertTrue(lastText().contains("already checked in") && lastText().contains("token is 3"), lastText());
        assertEquals(1, UP.checkIns.size());
    }

    @Test
    void twoFamilyMembersAreAskedInOneTapAndAllOfUsChecksBothIn() {
        UUID t = UUID.randomUUID();
        String a1 = UUID.randomUUID().toString(), a2 = UUID.randomUUID().toString();
        context(false, cand("ref-a", "Asha", a1, false), cand("ref-b", "Bina", a2, false));
        text(t, "hash-ci4", "CHECKIN ABCD2345");
        assertEquals("Who is checking in?", lastText());
        assertEquals(3, json.readTree(UP.sent.get(UP.sent.size() - 1)).path("buttons").size());
        assertTrue(UP.checkIns.isEmpty());
        tap(t, "hash-ci4", "All of us");
        assertEquals(2, UP.checkIns.size());
        assertTrue(UP.checkIns.get(0).contains(a1) && UP.checkIns.get(1).contains(a2));
        assertTrue(lastText().contains("Asha: you are checked in") && lastText().contains("Bina: you are checked in"), lastText());

        UUID t2 = UUID.randomUUID();
        UP.checkIns.clear();
        text(t2, "hash-ci4b", "CHECKIN ABCD2345");
        tap(t2, "hash-ci4b", "Bina");
        assertEquals(1, UP.checkIns.size());
        assertTrue(UP.checkIns.get(0).contains(a2));
        assertEquals("DONE", state(t2));
    }

    @Test
    void geofenceAsksForALocationUsesItOnceAndNeverStoresIt() {
        UUID t = UUID.randomUUID();
        context(true, cand("ref-a", "Asha", UUID.randomUUID().toString(), false));
        text(t, "hash-ci5", "CHECKIN ABCD2345");
        assertTrue(lastText().contains("share your location"));
        assertTrue(UP.checkIns.isEmpty(), "no token without a location");
        text(t, "hash-ci5", "hello?");
        assertTrue(lastText().contains("share your location"));
        assertEquals("CHECKIN", state(t));

        UP.checkInReplies.add(new Object[]{422, "{\"code\":\"OUTSIDE_GEOFENCE\"}"});
        String id = FakeUpstreams.id();
        UP.location(id, "hash-ci5", "12.98419155,77.59460077,15");
        engine.process(t, id, "whatsapp", "hash-ci5");
        assertEquals(1, UP.checkIns.size());
        assertTrue(UP.checkIns.get(0).contains("12.98419155"), "Core gets the location for its one distance check");
        assertTrue(lastText().contains("front desk has been told"), lastText());
        assertEquals("DONE", state(t));
        // nowhere in the Edge's own storage
        for (String needle : new String[]{"12.98419155", "77.59460077"}) {
            assertEquals(0, superuser().queryForObject("select count(*) from conversations where context::text like ?", Integer.class, "%" + needle + "%"));
            assertEquals(0, superuser().queryForObject("select count(*) from conversations where cast(conversations as text) like ?", Integer.class, "%" + needle + "%"));
            assertTrue(valkey.keys("*" + needle + "*").isEmpty());
        }
    }

    @Test
    void theImHereButtonStartsTheSameFlowOnTheWhatsappChannel() {
        UUID t = UUID.randomUUID();
        context(false, cand("ref-a", "Asha", UUID.randomUUID().toString(), false));
        String id = FakeUpstreams.id();
        UP.inbound(id, "hash-ci6", "I'm here", tokens.issue(t, CheckInFlow.IM_HERE, Duration.ofHours(1)));
        engine.process(t, id, "whatsapp", "hash-ci6");
        assertEquals(1, UP.checkIns.size());
        assertEquals("whatsapp", json.readTree(UP.checkIns.get(0).substring(UP.checkIns.get(0).indexOf(' ') + 1)).path("channel").asString());
        assertTrue(lastText().contains("Your token is"));
    }

    @Test
    void aCoreOutageFailsTheStepWithoutDroppingTheMessageAndTheRetryIsKeyedTheSame() {
        UUID t = UUID.randomUUID();
        String appt = UUID.randomUUID().toString();
        context(false, cand("ref-a", "Asha", appt, false));
        UP.checkInReplies.add(new Object[]{503, "{}"});
        String id = FakeUpstreams.id();
        UP.inbound(id, "hash-ci7", "CHECKIN ABCD2345", null);
        assertThrows(RuntimeException.class, () -> engine.process(t, id, "whatsapp", "hash-ci7"));
        engine.process(t, id, "whatsapp", "hash-ci7"); // the event is redelivered
        assertEquals(2, UP.checkIns.size());
        assertEquals(UP.checkIns.get(0).substring(0, UP.checkIns.get(0).indexOf(' ')), UP.checkIns.get(1).substring(0, UP.checkIns.get(1).indexOf(' ')),
                "same Idempotency-Key, so Core issues one token");
        assertTrue(lastText().contains("Your token is"));
    }

    @Test
    void aRedeliveredMessageChecksInOnce() {
        UUID t = UUID.randomUUID();
        context(false, cand("ref-a", "Asha", UUID.randomUUID().toString(), false));
        String id = FakeUpstreams.id();
        UP.inbound(id, "hash-ci8", "CHECKIN ABCD2345", null);
        engine.process(t, id, "whatsapp", "hash-ci8");
        engine.process(t, id, "whatsapp", "hash-ci8");
        assertEquals(1, UP.checkIns.size());
    }

    @Test
    void onlyTheCheckInCodeShapeIsClaimed() {
        assertTrue(CheckInFlow.claims(new FlowHandler.Message("text", " checkin ab12cd34 ", null)));
        assertFalse(CheckInFlow.claims(new FlowHandler.Message("text", "checkin", null)));
        assertFalse(CheckInFlow.claims(new FlowHandler.Message("text", "please checkin ABCD2345 now", null)));
        assertFalse(CheckInFlow.claims(new FlowHandler.Message("location", "CHECKIN ABCD2345", null)));
        assertTrue(CheckInFlow.claims(new FlowHandler.Message("button", "", CheckInFlow.IM_HERE)));
    }
}
