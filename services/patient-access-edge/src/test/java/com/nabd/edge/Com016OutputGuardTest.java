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

    @Test
    void anIdBelongsToTheFirstClinicItWasLearnedFor_aLaterProfileListingItDoesNotMakeItTheirs() {
        Outcome o = new Outcome(State.CHOOSING_DOCTOR, Map.of(), "Which doctor?", List.of(new Button("doc:" + shared, "Dr Common")));
        assertSame(o, guard.check(conv(a, "en"), o), "A listed it first");
        assertEquals(State.HANDOFF, guard.check(conv(b, "en"), o).next(), "B's profile listing A's doctor id is a leak");
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
