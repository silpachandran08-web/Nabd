package com.nabd.edge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ACC-041 (reminder buttons, travel time) and ACC-044 ('status', leave-now through the stream) against a scripted Core and Comms.
 * The real WhatsApp sandbox is Needs external action.
 */
class ReminderFlowTest extends EdgeTestBase {

    @Autowired ConversationEngine engine;
    @Autowired ButtonTokens tokens;
    @Autowired ObjectMapper json;
    @Autowired EtaNotifications notifications;
    @Autowired TravelBuffers travel;

    static final String REF = "ref-asha";
    UUID tenant;
    String contact;
    UUID appt;
    Instant start;

    @BeforeEach
    void setUp() {
        tenant = UUID.randomUUID();
        contact = "c" + UUID.randomUUID().toString().replace("-", "");
        appt = UUID.randomUUID();
        start = Instant.now().plus(Duration.ofHours(24));
        UP.coreHook = (line, body) -> {
            if (line.startsWith("GET /households/")) {
                return new String[]{"200", "{\"members\":[{\"patientRef\":\"" + REF + "\"}]}"};
            }
            if (line.startsWith("GET /appointments/" + appt)) {
                return line.contains("patientRef=" + REF) ? new String[]{"200", detail(0)} : new String[]{"403", "{}"};
            }
            if (line.startsWith("POST /appointments/" + appt + "/")) {
                return new String[]{"200", "{\"appointmentId\":\"" + appt + "\",\"status\":\"scheduled\"}"};
            }
            return null;
        };
    }

    String detail(long windowHours) {
        return "{\"appointmentId\":\"" + appt + "\",\"doctorId\":\"" + UUID.nameUUIDFromBytes("doc".getBytes()) + "\",\"startTime\":\"" + start
                + "\",\"status\":\"scheduled\",\"zone\":\"Asia/Kolkata\",\"windowHours\":" + windowHours + "}";
    }

    String act(String verb) {
        return ReminderIntent.action(verb, appt);
    }

    void tapAction(String action) {
        String id = FakeUpstreams.id();
        UP.inbound(id, contact, "", tokens.issue(tenant, action, Duration.ofHours(1)));
        engine.process(tenant, id, "whatsapp", contact);
    }

    void say(String text) {
        String id = FakeUpstreams.id();
        UP.inbound(id, contact, text, null);
        engine.process(tenant, id, "whatsapp", contact);
    }

    JsonNode last() {
        return json.readTree(UP.sent.get(UP.sent.size() - 1));
    }

    String text() {
        return last().path("text").asString();
    }

    void tapTitle(String title) {
        JsonNode m = last();
        for (JsonNode b : m.has("rows") ? m.path("rows") : m.path("buttons")) { // more than three choices go as a list message
            if (title.equals(b.path("title").asString())) {
                String id = FakeUpstreams.id();
                UP.inbound(id, contact, "", b.path("id").asString());
                engine.process(tenant, id, "whatsapp", contact);
                return;
            }
        }
        throw new AssertionError("no button " + title + " in " + UP.sent.get(UP.sent.size() - 1));
    }

    long calls(String prefix) {
        return UP.coreLog.stream().filter(l -> l.startsWith(prefix)).count();
    }

    long visits() {
        return superuser().queryForObject("select count(*) from visit_notification_state where tenant_id = ?::uuid", Long.class, tenant.toString());
    }

    @Test
    void confirmMarksTheAppointmentTracksTheVisitAndAsksTheTravelTimeOnce() {
        tapAction(act("confirm"));
        assertEquals(1, calls("POST /appointments/" + appt + "/confirm"));
        assertTrue(text().contains("confirmed"));
        assertEquals(3, last().path("buttons").size(), "10, 20, 30 min");
        assertTrue(text().contains("45 or 60"));
        assertEquals(1, visits(), "tracked for the leave-now alert");

        tapTitle("20 min");
        assertEquals(20, travel.get(tenant, REF).orElseThrow());
        assertTrue(text().contains("20 minutes"));

        tapAction(act("confirm")); // a later visit: the answer is reused, no second question
        assertTrue(text().contains("confirmed"));
        assertFalse(text().contains("How long"));
        assertEquals(0, last().path("buttons").size());
    }

    @Test
    void theTravelTimeCanBeAnsweredInTextAndChangedLater() {
        tapAction(act("confirm"));
        say("45");
        assertEquals(45, travel.get(tenant, REF).orElseThrow());
        say("change travel time");
        assertTrue(text().contains("How long"));
        tapTitle("10 min");
        assertEquals(10, travel.get(tenant, REF).orElseThrow());
        say("45 hello"); // not an answer: ignored by the intent
        assertEquals(10, travel.get(tenant, REF).orElseThrow());
    }

    @Test
    void anAppointmentThatIsNotTheirsIsNotConfirmed() {
        UP.coreHook = (line, body) -> line.startsWith("GET /households/") ? new String[]{"200", "{\"members\":[{\"patientRef\":\"someone-else\"}]}"}
                : line.startsWith("GET /appointments/") ? new String[]{"403", "{}"} : null;
        tapAction(act("confirm"));
        assertEquals(0, calls("POST"));
        assertTrue(text().contains("could not find"));
        assertEquals(0, visits());
    }

    @Test
    void rescheduleIsTwoTapsAndMovesTheBooking() {
        UP.slotsByDoctor.put(UUID.nameUUIDFromBytes("doc".getBytes()).toString(), "[\"" + start.plus(Duration.ofHours(2)) + "\",\"" + start.plus(Duration.ofHours(3))
                + "\",\"" + start.plus(Duration.ofHours(4)) + "\",\"" + start.plus(Duration.ofHours(5)) + "\"]");
        tapAction(act("resched")); // tap 1 (the reminder button)
        assertEquals(3, last().path("buttons").size(), "up to three times");
        String title = last().path("buttons").get(1).path("title").asString();
        assertTrue(title.length() <= 20, title);
        tapTitle(title); // tap 2
        assertEquals(1, calls("POST /appointments/" + appt + "/reschedule"));
        String body = UP.coreLog.stream().filter(l -> l.startsWith("POST /appointments/" + appt + "/reschedule")).findFirst().orElseThrow();
        assertTrue(body.contains("\"newStartTime\":\"" + start.plus(Duration.ofHours(3)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)), body);
        assertTrue(text().startsWith("Done. Your new time is"));
    }

    @Test
    void aTakenSlotOffersTheTimesAgain() {
        UP.slotsByDoctor.put(UUID.nameUUIDFromBytes("doc".getBytes()).toString(), "[\"" + start.plus(Duration.ofHours(2)) + "\"]");
        var base = UP.coreHook;
        UP.coreHook = (line, body) -> line.contains("/reschedule") ? new String[]{"409", "{\"code\":\"SLOT_TAKEN\"}"} : base.apply(line, body);
        tapAction(act("resched"));
        tapTitle(last().path("buttons").get(0).path("title").asString());
        assertTrue(text().startsWith("That time was just taken"));
        assertEquals(1, last().path("buttons").size());
    }

    /** COM-043: the reasons are ACC-007's coded cancel list; 'Transport' cancels with reason code transport in one tap. */
    @Test
    void cancelTakesOneTapForTheReasonAndFreesTheSlot() {
        tapAction(act("cancel"));
        List<String> codes = new java.util.ArrayList<>();
        last().path("rows").forEach(r -> codes.add(r.path("title").asString()));
        assertEquals(List.of("Schedule clash", "Illness", "Cost", "Transport", "Clinic rescheduled", "Other reason"), codes);
        tapTitle("Transport");
        String call = UP.coreLog.stream().filter(l -> l.startsWith("POST /appointments/" + appt + "/cancel")).findFirst().orElseThrow();
        assertTrue(call.contains("\"reasonCode\":\"transport\""), call);
        assertFalse(call.contains("\"note\""), call);
        assertTrue(text().contains("cancelled"));
    }

    @Test
    void otherCarriesTheNoteAcc007Requires() {
        tapAction(act("cancel"));
        tapTitle("Other reason");
        String call = UP.coreLog.stream().filter(l -> l.startsWith("POST /appointments/" + appt + "/cancel")).findFirst().orElseThrow();
        assertTrue(call.contains("\"reasonCode\":\"other\"") && call.contains("\"note\":\"Cancelled from the WhatsApp reminder\""), call);
    }

    @Test
    void insideTheCancellationWindowTheBotExplainsAndCallMeCreatesTheDeskException() {
        var base = UP.coreHook;
        start = Instant.now().plus(Duration.ofHours(10));
        UP.coreHook = (line, body) -> line.startsWith("GET /appointments/" + appt) ? new String[]{"200", detail(48)} : base.apply(line, body);
        tapAction(act("cancel"));
        assertEquals(0, calls("POST /appointments/" + appt + "/cancel"), "no cancel is attempted inside the window");
        assertTrue(text().contains("within 48 hours"));
        assertEquals(1, last().path("buttons").size());
        tapTitle("Call me");
        assertEquals(1, calls("POST /appointments/" + appt + "/call-me"));
        assertTrue(text().contains("desk will call"));

        // the same for reschedule, and Core's own refusal (the window changed between the two calls) is explained too
        tapAction(act("resched"));
        assertTrue(text().contains("within 48 hours"));
        UP.coreHook = (line, body) -> line.startsWith("POST /appointments/" + appt + "/cancel") ? new String[]{"422", "{\"code\":\"INSIDE_CANCELLATION_WINDOW\",\"windowHours\":24}"}
                : line.startsWith("GET /appointments/" + appt) ? new String[]{"200", detail(0)} : base.apply(line, body);
        tapAction(act("cancel"));
        tapTitle("Other reason");
        assertTrue(text().contains("within 24 hours"));
    }

    @Test
    void aTapIsReplayedAtCoreByTheSameKeyNotRepeated() {
        tapAction(act("confirm"));
        String key = UP.coreLog.stream().filter(l -> l.startsWith("POST /appointments/" + appt + "/confirm")).findFirst().orElseThrow();
        assertTrue(key.matches(".*key=[0-9a-f-]{36}$"), key);
    }

    // ---- ACC-044 ----------------------------------------------------------------------------------------------------------

    private void statusReturns(String itemJson) {
        UP.coreHook = (line, body) -> line.startsWith("GET /status/") ? new String[]{"200", "{\"items\":[" + itemJson + "]}"} : null;
    }

    @Test
    void statusInAnyLanguageOrByButtonAnswersWithTheShapedTextQuickly() {
        statusReturns("{\"appointmentId\":\"" + appt + "\",\"patientRef\":\"" + REF + "\",\"status\":\"arrived\",\"mode\":\"range\",\"text\":\"Expected 10:40-10:55\",\"p50\":null}");
        for (String word : new String[]{"status", "Status!", "स्थिति", "ಸ್ಥಿತಿ", "స్థితి", "الحالة"}) {
            long t0 = System.nanoTime();
            say(word);
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 3000, word);
            assertEquals("Expected 10:40-10:55", text(), word);
        }
        tapAction("status");
        assertEquals("Expected 10:40-10:55", text());
        say("hello"); // anything else is not the status intent: the state's own handler answers
        assertEquals("ok", text());
    }

    @Test
    void statusWithNoVisitOrAHiddenModeSaysSo() {
        statusReturns("");
        say("status");
        assertEquals("You have no visit at the clinic today.", text());
        statusReturns("{\"appointmentId\":\"" + appt + "\",\"patientRef\":\"" + REF + "\",\"status\":\"scheduled\",\"mode\":\"hidden\",\"text\":null,\"p50\":null}");
        say("status");
        assertTrue(text().contains("not shared a time estimate"));
    }

    // ---- leave-now through the real stream, state table and sweep --------------------------------------------------------

    private void eta(Instant p50, String mode, boolean arrived) {
        publish("ev:in:visit", "visit.eta.updated.v1", tenant, java.util.Map.of("appointmentId", appt.toString(), "p50", p50.toString(), "mode", mode,
                "arrived", String.valueOf(arrived)));
    }

    private long etaMessages() {
        return UP.sent.stream().filter(s -> s.contains("time to leave") || s.contains("expected time has changed")).count();
    }

    @Test
    void theLeaveNowMessageGoesOutOnceWhenP50MinusTravelMinusFiveIsReached() throws Exception {
        travel.put(tenant, REF, 30);
        notifications.track(tenant, appt.toString(), contact, REF);
        Instant p50 = Instant.now().plus(Duration.ofMinutes(60)); // leave-now is 25 min away
        eta(p50, "range", false);
        Thread.sleep(600);
        assertEquals(0, etaMessages(), "too early");

        eta(Instant.now().plus(Duration.ofMinutes(34)), "range", false); // now p50 - 30 - 5 <= now
        eventually(5000, () -> etaMessages() == 1);
        eta(Instant.now().plus(Duration.ofMinutes(33)), "range", false);
        Thread.sleep(600);
        assertEquals(1, etaMessages(), "once");
        assertEquals(1, superuser().queryForObject("select messages_sent from visit_notification_state where tenant_id = ?::uuid", Integer.class, tenant.toString()));
        assertTrue(UP.sent.stream().noneMatch(s -> s.contains("p50") || s.contains(appt.toString())), "no ids in the message");
    }

    @Test
    void theSweepPullsTheEtaFromCoreAndSendsLeaveNowOnTimeWithoutAnyEvent() {
        travel.put(tenant, REF, 30);
        notifications.track(tenant, appt.toString(), contact, REF);
        String p50 = Instant.now().plus(Duration.ofMinutes(20)).toString(); // leave-now was due 15 min ago
        statusReturns("{\"appointmentId\":\"" + appt + "\",\"patientRef\":\"" + REF + "\",\"status\":\"scheduled\",\"mode\":\"range\",\"text\":\"x\",\"p50\":\"" + p50 + "\"}");
        notifications.sweep();
        assertEquals(1, etaMessages());
        notifications.sweep();
        assertEquals(1, etaMessages(), "once");
        statusReturns("{\"appointmentId\":\"" + appt + "\",\"patientRef\":\"" + REF + "\",\"status\":\"arrived\",\"mode\":\"range\",\"text\":\"x\",\"p50\":\"" + p50 + "\"}");
        notifications.sweep();
        assertTrue(superuser().queryForObject("select arrived from visit_notification_state where tenant_id = ?::uuid", Boolean.class, tenant.toString()), "arrival read from Core");
    }

    @Test
    void nothingIsSentAfterArrivalOrInDelayOnlyAndHiddenModes() throws Exception {
        travel.put(tenant, REF, 30);
        notifications.track(tenant, appt.toString(), contact, REF);
        eta(Instant.now().plus(Duration.ofMinutes(20)), "delay_only", false);
        eta(Instant.now().plus(Duration.ofMinutes(20)), "hidden", false);
        Thread.sleep(800);
        assertEquals(0, etaMessages());
        publish("ev:in:scheduling", "scheduling.appointment.arrived.v1", tenant, java.util.Map.of("appointmentId", appt.toString()));
        eventually(5000, () -> superuser().queryForObject("select arrived from visit_notification_state where tenant_id = ?::uuid", Boolean.class, tenant.toString()));
        eta(Instant.now().plus(Duration.ofMinutes(20)), "range", false);
        Thread.sleep(600);
        assertEquals(0, etaMessages(), "arrived: no alert");
    }

    @Test
    void aCancelledVisitIsNoLongerTracked() {
        notifications.track(tenant, appt.toString(), contact, REF);
        assertEquals(1, visits());
        publish("ev:in:scheduling", "scheduling.appointment.cancelled.v1", tenant, java.util.Map.of("appointmentId", appt.toString(), "doctorId", UUID.randomUUID().toString()));
        eventually(5000, () -> visits() == 0);
    }

    @Test
    void theNotificationStateIsTenantIsolated() {
        notifications.track(tenant, appt.toString(), contact, REF);
        UUID other = UUID.randomUUID();
        notifications.onEta(other, appt.toString(), Instant.now(), "range", false); // another clinic's event for the same id touches nothing
        assertEquals(0, superuser().queryForObject("select count(*) from visit_notification_state where tenant_id = ?::uuid and current_p50 is not null", Long.class, tenant.toString()));
        travel.put(tenant, REF, 30);
        assertTrue(travel.get(other, REF).isEmpty(), "travel time of one clinic is invisible to another");
    }
}
