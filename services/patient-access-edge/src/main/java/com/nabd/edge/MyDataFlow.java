package com.nabd.edge;

import com.nabd.edge.FlowHandler.Button;
import com.nabd.edge.FlowHandler.Conversation;
import com.nabd.edge.FlowHandler.Message;
import com.nabd.edge.FlowHandler.Outcome;
import com.nabd.edge.FlowHandler.State;
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
import java.util.Set;

/**
 * PLT-047: 'my data' (or the {@code mydata} button) from any step asks for a copy of the patient's data. Whatever the state of the
 * phone, the person first types back a one-time code sent into the chat and then the date of birth on file (the COM-041 code and
 * Core's yes/no check; three wrong answers end the request), then picks whose data when the phone has a household, and Core decides
 * whether this phone may ask for that person (the patient themself, or the FULL-scope guardian of a minor: a BOOK_ONLY proxy or an
 * unapproved caregiver is refused with "contact the clinic"). The chat carries the acknowledgement only: no record content, and
 * the download link arrives later through the IDC-015 secure link with its own code. English copy only (shortcut: translated
 * wording comes with the template catalogue, as for Reverification).
 */
@Component
public class MyDataFlow implements Intent {

    static final String BUTTON = "mydata";
    static final String WHO = "mydata:who:";
    static final Set<String> WORDS = Set.of("my data", "mydata", "download my data", "copy of my data", "मेरा डेटा");
    static final int MAX_WRONG = 3;
    static final String ACK = "We have received your request for a copy of your data. We will send a secure link in this chat: it needs a code that we text to your "
            + "registered mobile number, and it works for 72 hours. Nothing about your records is shown in this chat.";
    static final String CONTACT = "Only the patient, or the approved guardian of a child, can ask for this data. Please contact the clinic.";
    static final String NO_RECORD = "We could not find a record for this number. Please contact the clinic.";
    static final String TOO_MANY = "Too many wrong answers, so we stopped. Please contact the clinic if you still need your data.";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PhoneVerifications phones;
    private final CoreClient core;

    MyDataFlow(PhoneVerifications phones, CoreClient core) {
        this.phones = phones;
        this.core = core;
    }

    @Override
    public Optional<Outcome> handle(Conversation c, Message m) {
        boolean asked = BUTTON.equals(m.buttonAction()) || (m.buttonAction() == null && m.text() != null && WORDS.contains(StatusIntent.normalise(m.text())));
        if (!asked) {
            return Optional.empty();
        }
        if (phones.status(c.tenantId(), c.contactHash()) == PhoneVerifications.Status.LOCKED) {
            return Optional.of(new Outcome(c.state(), c.context(), Reverification.LOCKED_TEXT));
        }
        if (core.household(c.tenantId(), c.contactHash()).isEmpty()) {
            return Optional.of(new Outcome(c.state(), c.context(), NO_RECORD));
        }
        byte[] raw = new byte[12];
        RANDOM.nextBytes(raw);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        Map<String, String> ctx = fresh(c);
        ctx.put("otpChal", challenge);
        ctx.put("otpHash", AddMemberFlow.hash(challenge, code));
        ctx.put("otpTry", "0");
        return Optional.of(new Outcome(State.MYDATA_OTP, ctx, "To protect your records we check it is you first. Your code is " + code + ". Please type it here."));
    }

    /** Only the language survives into the next step; the code challenge and the answers so far are not carried over. */
    private static Map<String, String> fresh(Conversation c) {
        Map<String, String> ctx = new HashMap<>();
        if (c.context().get("lang") != null) {
            ctx.put("lang", c.context().get("lang"));
        }
        return ctx;
    }

    /** One wrong answer: counted in the conversation (and in the phone's own COM-041 count when it is flagged); the third ends the request. */
    private static Outcome wrong(PhoneVerifications phones, Conversation c, Message m, State stay, String text) {
        if (phones.fail(c.tenantId(), c.contactHash(), m.messageId())) {
            return new Outcome(State.DONE, fresh(c), Reverification.LOCKED_TEXT);
        }
        int n = Integer.parseInt(c.context().getOrDefault("otpTry", "0")) + 1;
        if (n >= MAX_WRONG) {
            return new Outcome(State.DONE, fresh(c), TOO_MANY);
        }
        Map<String, String> ctx = new HashMap<>(c.context());
        ctx.put("otpTry", String.valueOf(n));
        return new Outcome(stay, ctx, text);
    }

    @Component
    static class Code implements FlowHandler {
        private final PhoneVerifications phones;

        Code(PhoneVerifications phones) {
            this.phones = phones;
        }

        @Override
        public State state() {
            return State.MYDATA_OTP;
        }

        @Override
        public Outcome handle(Conversation c, Message m) {
            String hash = c.context().getOrDefault("otpHash", ""), typed = m.text() == null ? "" : m.text().trim();
            if (!hash.isEmpty() && MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII),
                    AddMemberFlow.hash(c.context().get("otpChal"), typed).getBytes(StandardCharsets.US_ASCII))) {
                Map<String, String> ctx = fresh(c);
                ctx.put("otpTry", c.context().getOrDefault("otpTry", "0"));
                return new Outcome(State.MYDATA_DOB, ctx, "Thank you. Now please type your date of birth as DD-MM-YYYY.");
            }
            return wrong(phones, c, m, State.MYDATA_OTP, "That code is not right. Please try again.");
        }
    }

    @Component
    static class Dob implements FlowHandler {
        private final PhoneVerifications phones;
        private final CoreClient core;
        private final PrivacyClient privacy;
        private final Clock clock;

        Dob(PhoneVerifications phones, CoreClient core, PrivacyClient privacy, Clock clock) {
            this.phones = phones;
            this.core = core;
            this.privacy = privacy;
            this.clock = clock;
        }

        @Override
        public State state() {
            return State.MYDATA_DOB;
        }

        @Override
        public Outcome handle(Conversation c, Message m) {
            LocalDate dob = m.text() == null ? null : AddMemberFlow.parseDob(m.text(), LocalDate.now(clock));
            if (dob == null) { // unreadable is not a wrong answer
                return new Outcome(State.MYDATA_DOB, c.context(), "Please type your date of birth as DD-MM-YYYY, for example 07-03-1990.");
            }
            if (!core.secondFactorMatches(c.tenantId(), c.contactHash(), dob)) {
                return wrong(phones, c, m, State.MYDATA_DOB, "That date of birth does not match our records. Please try again as DD-MM-YYYY.");
            }
            phones.verified(c.tenantId(), c.contactHash(), Reverification.METHOD); // a flagged phone is cleared by the same two checks (no-op otherwise)
            List<CoreClient.Member> members = core.household(c.tenantId(), c.contactHash());
            if (members.isEmpty()) {
                return new Outcome(State.DONE, fresh(c), NO_RECORD);
            }
            if (members.size() == 1) {
                return submit(privacy, c, m, members.get(0).patientRef());
            }
            Map<String, String> ctx = fresh(c);
            return new Outcome(State.MYDATA_WHO, ctx, "Whose data would you like?", members.stream().limit(9)
                    .map(k -> new Button(WHO + k.patientRef(), cut(k.firstName() + " (" + k.years() + ")"))).toList());
        }
    }

    @Component
    static class Who implements FlowHandler {
        private final CoreClient core;
        private final PrivacyClient privacy;

        Who(CoreClient core, PrivacyClient privacy) {
            this.core = core;
            this.privacy = privacy;
        }

        @Override
        public State state() {
            return State.MYDATA_WHO;
        }

        @Override
        public Outcome handle(Conversation c, Message m) {
            String a = m.buttonAction();
            String ref = a != null && a.startsWith(WHO) ? a.substring(WHO.length()) : null;
            if (ref == null || !core.householdRefs(c.tenantId(), c.contactHash()).contains(ref)) {
                return new Outcome(State.MYDATA_WHO, c.context(), "Please tap one of the names above.");
            }
            return submit(privacy, c, m, ref);
        }
    }

    /** Core's answer decides: created, already open, or the refusal. A refusal is not an outage: the person is told to contact the clinic. */
    private static Outcome submit(PrivacyClient privacy, Conversation c, Message m, String personRef) {
        String text;
        try {
            privacy.request(c.tenantId(), personRef, c.contactHash(), "access", "mydata:" + m.messageId());
            text = ACK;
        } catch (Http.UpstreamStatusException e) {
            if (e.status == 409) {
                text = "You already have a request for your data open. We will send the secure link in this chat as soon as it is ready.";
            } else if (e.status == 403 && e.body.contains("PROXY_NOT_APPROVED")) {
                text = CONTACT;
            } else if (e.status == 403 || e.status == 404 || e.status == 422) {
                text = "We could not start this request from this number. Please contact the clinic.";
            } else {
                throw e;
            }
        }
        return new Outcome(State.DONE, fresh(c), text);
    }

    private static String cut(String s) {
        return s.length() <= 20 ? s : s.substring(0, 20);
    }
}
