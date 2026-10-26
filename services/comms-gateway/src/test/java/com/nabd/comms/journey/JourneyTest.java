package com.nabd.comms.journey;

import com.nabd.comms.provider.Provider;
import com.nabd.comms.testkit.ProviderStub;
import com.nabd.comms.testkit.VirtualPatient;
import com.nabd.comms.testkit.VirtualPatient.Webhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * COM-010: how to write a journey test (docs/runbooks/comms-journey-tests.md). A VirtualPatient posts signed inbound webhooks to an endpoint and
 * asserts the replies the stub received. The bot here is a SCRIPTED STAND-IN for the patient-edge booking flows (COM-0xx, another service):
 * it proves the harness (signature check, button payloads, STOP, elapsed time) and is the template bot-flow authors copy; the real flows plug
 * into the same {@code Consumer<Webhook>} endpoint.
 */
class JourneyTest {

    static final ObjectMapper JSON = new ObjectMapper();
    final byte[] secret = new byte[32];
    ProviderStub stub;
    TestBot bot;

    @BeforeEach
    void setUp() {
        java.util.Arrays.fill(secret, (byte) 9);
        stub = new ProviderStub();
        bot = new TestBot(stub, secret);
    }

    /** Verifies X-Hub-Signature-256, reads the message, replies through the provider port. */
    static class TestBot {
        final ProviderStub provider;
        final byte[] secret;
        final Set<String> optedOut = new HashSet<>();
        final Map<String, String> booking = new HashMap<>();
        final Map<String, String> awaiting = new HashMap<>();

        TestBot(ProviderStub provider, byte[] secret) { this.provider = provider; this.secret = secret; }

        void receive(Webhook w) {
            if (!VirtualPatient.sign(secret, w.body()).equals(w.signature())) throw new SecurityException("bad signature");
            JsonNode m = JSON.readTree(w.body()).path("entry").get(0).path("changes").get(0).path("value").path("messages").get(0);
            String from = m.path("from").asString();
            String in = m.path("type").asString().equals("text") ? m.path("text").path("body").asString()
                    : m.path("interactive").path("button_reply").path("id").asString();
            if (in.equalsIgnoreCase("STOP")) { optedOut.add(from); reply(from, "You are unsubscribed. Reply START to resume."); return; }
            if (optedOut.contains(from)) return; // no reply to an opted-out number
            switch (in.toLowerCase()) {
                case "hi" -> reply(from, "Welcome. Tap book or reschedule.");
                case "book" -> { awaiting.put(from, "book"); reply(from, "Pick a slot: slot:10:00 or slot:11:00"); }
                case "reschedule" -> {
                    if (!booking.containsKey(from)) reply(from, "No booking found.");
                    else { awaiting.put(from, "reschedule"); reply(from, "Pick a new slot: slot:10:00 or slot:11:00"); }
                }
                default -> {
                    if (in.startsWith("slot:")) {
                        String what = awaiting.remove(from);
                        booking.put(from, in.substring(5));
                        reply(from, ("reschedule".equals(what) ? "Moved to " : "Booked for ") + in.substring(5));
                    } else reply(from, "Sorry, I did not get that.");
                }
            }
        }

        void reply(String to, String text) { provider.send(new Provider.OutboundRequest(UUID.randomUUID(), "pn1", to, text)); }
    }

    VirtualPatient patient(String phone) {
        return new VirtualPatient(phone, "pn1", secret, bot::receive, stub);
    }

    @Test
    void bookJourneyPassesAndReportsElapsedTime() {
        var p = patient("+15550001").say("Hi").expectReply("Welcome").tap("book").expectReply("Pick a slot").tap("slot:10:00").expectReply("Booked for 10:00");
        System.out.println("COM-010 journey book: " + p.elapsed().toMillis() + " ms");
        assertThat(p.elapsed()).as("T-162 target is a median under 60 s end to end").isLessThan(Duration.ofSeconds(60));
        assertThat(bot.booking).containsEntry("+15550001", "10:00");
    }

    @Test
    void rescheduleJourneyMovesTheBooking() {
        var p = patient("+15550002").say("Hi").expectReply("Welcome").tap("book").expectReply("Pick").tap("slot:10:00").expectReply("Booked")
                .tap("reschedule").expectReply("Pick a new slot").tap("slot:11:00").expectReply("Moved to 11:00");
        System.out.println("COM-010 journey reschedule: " + p.elapsed().toMillis() + " ms");
        assertThat(bot.booking).containsEntry("+15550002", "11:00");
    }

    @Test
    void stopJourneyUnsubscribesAndTheBotGoesQuiet() {
        var p = patient("+15550003").say("Hi").expectReply("Welcome").say("STOP").expectReply("unsubscribed");
        p.say("Hi").tap("book");
        assertThat(p.unreadReplies()).as("nothing is sent to an opted-out number").isZero();
        assertThat(bot.optedOut).contains("+15550003");
    }

    @Test
    void aMissingExpectedReplyFailsTheJourney() {
        var p = patient("+15550004").say("Hi").expectReply("Welcome");
        assertThatThrownBy(() -> p.expectReply("anything")).isInstanceOf(AssertionError.class).hasMessageContaining("no reply");
        assertThatThrownBy(() -> patient("+15550005").say("Hi").expectReply("Goodbye")).isInstanceOf(AssertionError.class).hasMessageContaining("does not contain");
    }

    @Test
    void anInboundWebhookWithTheWrongSignatureIsRejected() {
        var forged = new VirtualPatient("+15550006", "pn1", new byte[32], bot::receive, stub);
        assertThatThrownBy(() -> forged.say("Hi")).isInstanceOf(SecurityException.class);
        assertThat(stub.sentTo("+15550006")).isEmpty();
    }
}
