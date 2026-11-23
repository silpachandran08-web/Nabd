package com.nabd.edge;

import com.nabd.edge.FlowHandler.Button;
import com.nabd.edge.FlowHandler.Conversation;
import com.nabd.edge.FlowHandler.Message;
import com.nabd.edge.FlowHandler.Outcome;
import com.nabd.edge.FlowHandler.State;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * COM-041: before anything from the records side is served on a phone that is not verified (PhoneVerifications), the patient types back
 * a one-time code sent into the chat and then the date of birth on file. The code is the IDC-010 in-chat code (hashed with a random
 * challenge in the conversation context, single use); the date of birth is checked by Core, which answers yes or no only. Records
 * requests are "My appointment" and 'status' (word or button); booking stays open (BookingFlow lists no family names meanwhile).
 * English copy only (shortcut: translated wording comes with the template catalogue, as for ReminderIntent and AddMemberFlow).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // before StatusIntent, so 'status' cannot skip the check
class Reverification implements Intent {

    static final String METHOD = "otp+dob";
    static final String LOCKED_TEXT = "For your safety, records are locked on this number for 24 hours after too many wrong answers. "
            + "The clinic has been told and will help you. You can still book an appointment.";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PhoneVerifications phones;

    Reverification(PhoneVerifications phones) {
        this.phones = phones;
    }

    static boolean recordsRequest(Message m) {
        String a = m.buttonAction();
        return "menu:mine".equals(a) || StatusIntent.BUTTON.equals(a) || RecordsFlow.claims(m) // COM-050: 'My records' and every tap in it
                || (a == null && m.text() != null && StatusIntent.WORDS.contains(StatusIntent.normalise(m.text())));
    }

    @Override
    public Optional<Outcome> handle(Conversation c, Message m) {
        if (!recordsRequest(m)) {
            return Optional.empty();
        }
        return switch (phones.status(c.tenantId(), c.contactHash())) {
            case VERIFIED -> Optional.empty();
            case LOCKED -> Optional.of(new Outcome(c.state(), c.context(), LOCKED_TEXT));
            case NEEDS_REVERIFY -> Optional.of(askCode(c));
        };
    }

    private static Outcome askCode(Conversation c) {
        byte[] raw = new byte[12];
        RANDOM.nextBytes(raw);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        Map<String, String> ctx = lang(c);
        ctx.put("otpChal", challenge);
        ctx.put("otpHash", AddMemberFlow.hash(challenge, code));
        return new Outcome(State.REVERIFY_OTP, ctx, "This number needs to be checked again before we can show records. Your code is " + code
                + ". Please type it here.");
    }

    private static Map<String, String> lang(Conversation c) {
        Map<String, String> ctx = new HashMap<>();
        if (c.context().get("lang") != null) {
            ctx.put("lang", c.context().get("lang"));
        }
        return ctx;
    }

    private static Outcome locked(Conversation c) {
        return new Outcome(State.DONE, lang(c), LOCKED_TEXT);
    }

    /** Typed code, compared in constant time; a wrong one counts towards the lock. */
    @Component
    static class Code implements FlowHandler {
        private final PhoneVerifications phones;

        Code(PhoneVerifications phones) {
            this.phones = phones;
        }

        @Override
        public State state() {
            return State.REVERIFY_OTP;
        }

        @Override
        public Outcome handle(Conversation c, Message m) {
            if (phones.status(c.tenantId(), c.contactHash()) == PhoneVerifications.Status.LOCKED) {
                return locked(c);
            }
            String hash = c.context().getOrDefault("otpHash", ""), typed = m.text() == null ? "" : m.text().trim();
            if (!hash.isEmpty() && MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII),
                    AddMemberFlow.hash(c.context().get("otpChal"), typed).getBytes(StandardCharsets.US_ASCII))) {
                return new Outcome(State.REVERIFY_DOB, lang(c), "Thank you. Now please type your date of birth as DD-MM-YYYY.");
            }
            if (phones.fail(c.tenantId(), c.contactHash(), m.messageId())) {
                return locked(c);
            }
            return new Outcome(State.REVERIFY_OTP, c.context(), "That code is not right. Please try again.");
        }
    }

    /** Second factor: the date of birth of a patient registered on this phone. Only reachable after the right code. */
    @Component
    static class Dob implements FlowHandler {
        private final PhoneVerifications phones;
        private final CoreClient core;
        private final Clock clock;

        Dob(PhoneVerifications phones, CoreClient core, Clock clock) {
            this.phones = phones;
            this.core = core;
            this.clock = clock;
        }

        @Override
        public State state() {
            return State.REVERIFY_DOB;
        }

        @Override
        public Outcome handle(Conversation c, Message m) {
            if (phones.status(c.tenantId(), c.contactHash()) == PhoneVerifications.Status.LOCKED) {
                return locked(c);
            }
            LocalDate dob = m.text() == null ? null : AddMemberFlow.parseDob(m.text(), LocalDate.now(clock));
            if (dob == null) { // unreadable is not a wrong answer
                return new Outcome(State.REVERIFY_DOB, c.context(), "Please type your date of birth as DD-MM-YYYY, for example 07-03-1990.");
            }
            if (core.secondFactorMatches(c.tenantId(), c.contactHash(), dob)) {
                phones.verified(c.tenantId(), c.contactHash(), METHOD);
                String l = BookingCopy.supported(c.context().get("lang")) ? c.context().get("lang") : "en";
                return new Outcome(State.MENU, lang(c), "Thank you, this number is verified again.",
                        List.of(new Button("menu:book", BookingCopy.t(l, "menu.book")), new Button("menu:mine", BookingCopy.t(l, "menu.mine")),
                                new Button("menu:talk", BookingCopy.t(l, "menu.talk"))));
            }
            if (phones.fail(c.tenantId(), c.contactHash(), m.messageId())) {
                return locked(c);
            }
            return new Outcome(State.REVERIFY_DOB, c.context(), "That date of birth does not match our records. Please try again as DD-MM-YYYY.");
        }
    }
}
