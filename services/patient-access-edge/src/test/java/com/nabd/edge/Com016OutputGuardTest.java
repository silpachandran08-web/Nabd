package com.nabd.edge;

import com.nabd.edge.FlowHandler.Button;
import com.nabd.edge.FlowHandler.Conversation;
import com.nabd.edge.FlowHandler.Outcome;
import com.nabd.edge.FlowHandler.State;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** COM-016.2 unit: the output guard (no Spring, no database). */
class Com016OutputGuardTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final OutputGuard guard = new OutputGuard(meters);
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), docA = UUID.randomUUID(), docB = UUID.randomUUID(), shared = UUID.randomUUID();

    {
        guard.learn(a, new CoreClient.Profile("Lotus Clinic", "Asia/Kolkata", List.of(new CoreClient.Doctor(docA, "Dr Iyer"), new CoreClient.Doctor(shared, "Dr Common"))));
        guard.learn(b, new CoreClient.Profile("Banyan Kids", "Asia/Kolkata", List.of(new CoreClient.Doctor(docB, "Dr Mehta"), new CoreClient.Doctor(shared, "Dr Common"))));
    }

    private Conversation conv(String lang) {
        return conv(a, lang);
    }

    private Conversation conv(UUID tenant, String lang) {
        return new Conversation(UUID.randomUUID(), tenant, "whatsapp", "hash", State.CHOOSING_DOCTOR, lang == null ? Map.of() : Map.of("lang", lang, "who", "ref"), 1);
    }

    private double blocked(String reason) {
        var c = meters.find("edge_output_guard_blocked_total").tag("reason", reason).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void ownContentPasses_unchanged() {
        Outcome o = new Outcome(State.CHOOSING_DOCTOR, Map.of("lang", "en"), "Welcome to Lotus Clinic. Which doctor would you like to see?",
                List.of(new Button("doc:" + docA, "Dr Iyer"), new Button("doc:" + shared, "Dr Common")));
        assertSame(o, guard.check(conv("en"), o));
        assertEquals(0, blocked("foreign_tenant"));
    }

    @Test
    void anotherTenantsIdIsBlocked_fallbackSent_handedToReception_alertRaised() {
        for (Outcome o : List.of(
                new Outcome(State.MENU, Map.of(), "see " + b, List.of()),                                       // tenant id in the text
                new Outcome(State.CHOOSING_DOCTOR, Map.of(), "Which doctor?", List.of(new Button("doc:" + docB, "Dr X"))), // foreign doctor id in an action
                new Outcome(State.MENU, Map.of(), "Try Banyan Kids instead", List.of()),                        // foreign clinic name
                new Outcome(State.CHOOSING_DOCTOR, Map.of(), "Which doctor?", List.of(new Button("doc:" + docA, "DR MEHTA"))))) { // foreign doctor name, any case
            Outcome g = guard.check(conv("en"), o);
            assertEquals(State.HANDOFF, g.next());
            assertEquals("Let me connect you to the clinic.", g.reply());
            assertTrue(g.buttons().isEmpty());
            assertEquals(Map.of("lang", "en"), g.context(), "only the language is kept");
        }
        assertEquals(4, blocked("foreign_tenant"));
        assertEquals("हम आपको क्लिनिक से जोड़ रहे हैं।", guard.check(conv("hi"), new Outcome(State.MENU, Map.of(), "see " + b)).reply());
        assertEquals("Let me connect you to the clinic.", guard.check(conv(null), new Outcome(State.MENU, Map.of(), "see " + b)).reply());
    }

    @Test
    void discoveryIsNeverOfferedInClinicContext() {
        Outcome o = new Outcome(State.MENU, Map.of(), "What would you like to do?", List.of(new Button("menu:book", "Book"), new Button("discover:nearby", "Find a clinic")));
        assertEquals(State.HANDOFF, guard.check(conv("en"), o).next());
        assertEquals(1, blocked("discovery"));
    }

    // T1-BUG-6: ownership is the conversation's own profile, never "the first clinic this instance saw"
    @Test
    void aDoctorInBothClinicsProfilesIsBlockedForNeither_whicheverProfileWasReadFirst() {
        Outcome o = new Outcome(State.CHOOSING_DOCTOR, Map.of("doctorId", shared.toString()), "Which day suits you? Dr Common", List.of(new Button("doc:" + shared, "Dr Common")));
        assertSame(o, guard.check(conv(a, "en"), o), "A's profile lists the doctor");
        assertSame(o, guard.check(conv(b, "en"), o), "B's profile lists the doctor too: B's own patient is not handed to reception");
        OutputGuard bFirst = new OutputGuard(meters);
        bFirst.learn(b, new CoreClient.Profile("Banyan Kids", "Asia/Kolkata", List.of(new CoreClient.Doctor(shared, "Dr Common", b))));
        bFirst.learn(a, new CoreClient.Profile("Lotus Clinic", "Asia/Kolkata", List.of(new CoreClient.Doctor(shared, "Dr Common", a))));
        assertSame(o, bFirst.check(conv(a, "en"), o));
        assertSame(o, bFirst.check(conv(b, "en"), o));
        assertEquals(0, blocked("foreign_tenant"));
    }

    // T1-BUG-5/6: Core lists B's doctor in A's profile; the row says whose staff it is, so it is foreign in A on every path and in every order
    @Test
    void aProfileRowOfAnotherClinicsStaffIsForeign_onAColdInstance_onThePackagePath_andNeverBlocksItsOwnClinic() {
        CoreClient.Profile leaking = new CoreClient.Profile("Lotus Clinic", "Asia/Kolkata", List.of(new CoreClient.Doctor(docA, "Dr Iyer", a), new CoreClient.Doctor(docB, "Dr Mehta", b)));
        CoreClient.Profile honestB = new CoreClient.Profile("Banyan Kids", "Asia/Kolkata", List.of(new CoreClient.Doctor(docB, "Dr Mehta", b)));
        List<Outcome> leaks = List.of(
                new Outcome(State.CHOOSING_DOCTOR, Map.of(), "Which doctor?", List.of(new Button("doc:" + docA, "Dr Iyer"), new Button("doc:" + docB, "Dr Mehta"))), // the doctor list
                new Outcome(State.CHOOSING_DAY, Map.of("doctorId", docB.toString()), "Which day suits you?", List.of(new Button("day:2026-11-20", "20 Nov"))), // package path: only eligible doctor, id only in the context
                new Outcome(State.CONFIRMING, Map.of(), "Doctor: Dr Mehta\nClinic: Lotus Clinic", List.of(new Button("confirm", "Confirm"))));              // the name alone
        Outcome ownB = new Outcome(State.CHOOSING_DAY, Map.of("doctorId", docB.toString()), "Which day suits you? Dr Mehta", List.of(new Button("doc:" + docB, "Dr Mehta")));
        Outcome ownA = new Outcome(State.CHOOSING_DAY, Map.of("doctorId", docA.toString()), "Which day suits you? Dr Iyer", List.of());
        for (int order = 0; order < 2; order++) {
            OutputGuard g = new OutputGuard(meters); // a cold instance
            if (order == 1) {
                g.learn(b, honestB); // B served first; in order 0 this instance has never heard of B when the leak arrives
            }
            g.learn(a, leaking);
            for (Outcome o : leaks) {
                Outcome blockedReply = g.check(conv(a, "en"), o);
                assertEquals(State.HANDOFF, blockedReply.next(), "order " + order + ": " + o.reply());
                assertEquals("Let me connect you to the clinic.", blockedReply.reply());
            }
            assertSame(ownA, g.check(conv(a, "en"), ownA), "A's own doctor still passes");
            g.learn(b, honestB); // B's own conversation reads B's own profile
            assertSame(ownB, g.check(conv(b, "en"), ownB), "order " + order + ": B's own patient, B's own doctor");
        }
        assertEquals(6, blocked("foreign_tenant"));
    }

    // T1-BUG-5: a doctor that is not in the conversation's own profile is foreign even when this instance has never heard of the other clinic
    @Test
    void aDoctorOutsideTheClinicsOwnProfileIsForeign_withNoKnowledgeOfAnyOtherClinic() {
        OutputGuard cold = new OutputGuard(meters);
        UUID stranger = UUID.randomUUID();
        Outcome offered = new Outcome(State.CHOOSING_DOCTOR, Map.of(), "Which doctor?", List.of(new Button("doc:" + stranger, "Dr X")));
        Outcome chosen = new Outcome(State.CHOOSING_DAY, Map.of("doctorId", stranger.toString()), "Which day suits you?", List.of());
        assertEquals(State.HANDOFF, cold.check(conv(a, "en"), offered).next(), "nothing read yet: not known to be this clinic's");
        cold.learn(a, new CoreClient.Profile("Lotus Clinic", "Asia/Kolkata", List.of(new CoreClient.Doctor(docA, "Dr Iyer"))));
        assertEquals(State.HANDOFF, cold.check(conv(a, "en"), offered).next());
        assertEquals(State.HANDOFF, cold.check(conv(a, "en"), chosen).next());
        Outcome own = new Outcome(State.CHOOSING_DAY, Map.of("doctorId", docA.toString()), "Which day suits you?", List.of(new Button("doc:" + docA, "Dr Iyer")));
        assertSame(own, cold.check(conv(a, "en"), own));
        cold.forget();
        cold.learn(a, new CoreClient.Profile("Lotus Clinic", "Asia/Kolkata", List.of(new CoreClient.Doctor(docA, "Dr Iyer"), new CoreClient.Doctor(stranger, "Dr X"))));
        assertSame(offered, cold.check(conv(a, "en"), offered), "the profile is read again on every booking step: a new doctor of the clinic is its own at once");
    }

    @Test
    void aClinicNamedWithTheBotsOwnWordsNeverBlocksTheBotsSentences() {
        guard.learn(UUID.randomUUID(), new CoreClient.Profile("Clinic", "UTC", List.of(new CoreClient.Doctor(UUID.randomUUID(), "Me"))));
        Outcome o = new Outcome(State.MENU, Map.of(), "Welcome to Lotus Clinic. What would you like to do?",
                List.of(new Button("menu:book", "Book"), new Button("menu:talk", "Talk to clinic"), new Button("who:x", "Me")));
        assertSame(o, guard.check(conv("en"), o));
    }

    @Test
    void namesAreWholeWords_andANameTheClinicAlsoUsesIsNotForeign() {
        assertFalse(OutputGuard.containsWord("banyan kidsville", "banyan kids"));
        assertTrue(OutputGuard.containsWord("go to banyan kids.", "banyan kids"));
        Outcome common = new Outcome(State.CONFIRMING, Map.of(), "Doctor: Dr Common\nClinic: Lotus Clinic", List.of());
        assertSame(common, guard.check(conv("en"), common));
        Outcome noReply = new Outcome(State.HANDOFF, Map.of(), null);
        assertSame(noReply, guard.check(conv("en"), noReply));
        assertTrue(guard.foreignIn(UUID.randomUUID(), "Banyan Kids").isPresent(), "an unknown clinic's conversation still sees known clinics as foreign");
    }
}
