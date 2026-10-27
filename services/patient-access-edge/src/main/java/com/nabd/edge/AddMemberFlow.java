package com.nabd.edge;

import com.nabd.edge.FlowHandler.Button;
import com.nabd.edge.FlowHandler.Conversation;
import com.nabd.edge.FlowHandler.Message;
import com.nabd.edge.FlowHandler.Outcome;
import com.nabd.edge.FlowHandler.State;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.DateTimeException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * IDC-010: a caregiver adds a family member in the chat. relation (tap), name (typed), date of birth (typed DD-MM-YYYY), own phone?
 * (adults only: tap, then the number), consent (tap; for a minor the guardian's code typed back). At most 3 taps for a child, 4 for an adult.
 * A minor's consent is the guardian's: the Edge sends a one-time code into the same chat (the guardian's own WhatsApp number), checks the
 * typed code, and relays the challenge id to Core as evidence. Core decides everything else (guardian, consent rows, proxy scope, desk
 * task). The conversation context holds the typed answers only until Core accepts them (the step ends with an empty context).
 * Copy is English only for now: hi / kn / te wording needs native and legal review (Needs external action).
 */
@Configuration
public class AddMemberFlow {

    static final String REL_CHILD = "member_rel_child", REL_PARENT = "member_rel_parent", REL_OTHER = "member_rel_other";
    static final String PHONE_YES = "member_phone_yes", PHONE_NO = "member_phone_no";
    static final String CONSENT_YES = "member_consent_yes", CONSENT_NO = "member_consent_no";
    static final int OTP_TRIES = 3;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CoreClient core;
    private final EdgeProperties props;
    private final Clock clock;

    AddMemberFlow(CoreClient core, EdgeProperties props, Clock clock) {
        this.core = core;
        this.props = props;
        this.clock = clock;
    }

    /** Entry point for the menu: the caller knows the caregiver's reference (household lookup, ACC-038). */
    public Outcome begin(Conversation c, String caregiverRef, String lang) {
        Map<String, String> ctx = new HashMap<>();
        ctx.put("caregiverRef", caregiverRef);
        ctx.put("lang", ConsentCopy.supported(lang) ? lang : ConsentCopy.DEFAULT);
        return new Outcome(State.MEMBER_RELATION, ctx, "Who would you like to add?",
                List.of(new Button(REL_CHILD, "My child"), new Button(REL_PARENT, "My parent"), new Button(REL_OTHER, "Someone else")));
    }

    private static Map<String, String> with(Conversation c, String... kv) {
        Map<String, String> m = new HashMap<>(c.context());
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private Outcome again(Conversation c, String text) {
        return new Outcome(c.state(), c.context(), text, buttonsFor(c.state()));
    }

    private static List<Button> buttonsFor(State s) {
        return switch (s) {
            case MEMBER_RELATION -> List.of(new Button(REL_CHILD, "My child"), new Button(REL_PARENT, "My parent"), new Button(REL_OTHER, "Someone else"));
            case MEMBER_PHONE_CHOICE -> List.of(new Button(PHONE_YES, "Has own phone"), new Button(PHONE_NO, "No phone"));
            default -> List.of();
        };
    }

    private static Outcome done(String text) {
        return new Outcome(State.DONE, Map.of(), text);
    }

    private boolean minor(Conversation c) {
        return Period.between(LocalDate.parse(c.context().get("memDob")), LocalDate.now(clock)).getYears() < 18;
    }

    private Outcome askConsent(Conversation c, Map<String, String> ctx) {
        Conversation next = new Conversation(c.id(), c.tenantId(), c.channel(), c.contactHash(), State.MEMBER_CONSENT, ctx, c.version());
        if (minor(next)) {
            CoreClient.Notice n = core.currentNotice(c.tenantId(), ctx.get("lang"));
            ctx.put("noticeVersion", n.version());
            return new Outcome(State.MEMBER_CONSENT, ctx, "To add your child we need your consent as parent or guardian. Please read the privacy notice: "
                    + props.noticeBaseUrl() + n.path() + " . Do you agree that the clinic may keep your child's health data to look after them?",
                    List.of(new Button(CONSENT_YES, "I agree"), new Button(CONSENT_NO, "Cancel")));
        }
        return new Outcome(State.MEMBER_CONSENT, ctx, "Please confirm that this person knows you are adding them and agrees to it. The clinic will confirm their consent with them.",
                List.of(new Button(CONSENT_YES, "Yes, add them"), new Button(CONSENT_NO, "Cancel")));
    }

    private Map<String, Object> body(Conversation c, String challenge) {
        Map<String, String> x = c.context();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("relation", x.get("memRel"));
        m.put("name", x.get("memName"));
        m.put("dob", x.get("memDob"));
        m.put("language", x.get("lang"));
        if (x.get("memPhone") != null) {
            m.put("phone", x.get("memPhone"));
        }
        if (challenge != null) {
            m.put("guardianConsent", Map.of("otpChallengeId", challenge, "noticeVersion", x.get("noticeVersion")));
        }
        return m;
    }

    /** Relays to Core and turns its answer into the last message. A stale notice shows the consent step again; a rule refusal ends the flow. */
    private Outcome submit(Conversation c, String challenge) {
        CoreClient.AddedMember m;
        try {
            m = core.addMember(c.tenantId(), c.context().get("caregiverRef"), body(c, challenge));
        } catch (Http.UpstreamStatusException e) {
            if (e.status == 409) {
                return askConsent(c, with(c, "otpChal", "", "otpHash", "", "otpTry", "0"));
            }
            if (e.status == 422 || e.status == 400 || e.status == 404) {
                return done("We could not add this person from chat. Please ask the clinic desk to help.");
            }
            throw e;
        }
        String first = c.context().get("memName").trim().split("\\s+")[0];
        return done(switch (m.proxyStatus()) {
            case "guardian_consented" -> first + " is added. You can book for " + first + " now.";
            case "awaiting_consent" -> first + " is added. We are waiting for their own consent before their records can be used.";
            default -> first + " is added for booking. The clinic will confirm their consent at the next visit.";
        });
    }

    private static String hash(String challenge, String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((challenge + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static LocalDate parseDob(String text, LocalDate today) {
        try {
            String[] p = text.trim().split("[-/. ]+");
            if (p.length != 3 || p[2].length() != 4) {
                return null;
            }
            LocalDate d = LocalDate.of(Integer.parseInt(p[2]), Integer.parseInt(p[1]), Integer.parseInt(p[0]));
            return d.isAfter(today) || d.isBefore(today.minusYears(120)) ? null : d;
        } catch (NumberFormatException | DateTimeException e) {
            return null;
        }
    }

    private FlowHandler step(State state, BiFunction<Conversation, Message, Outcome> fn) {
        return new FlowHandler() {
            @Override
            public State state() {
                return state;
            }

            @Override
            public Outcome handle(Conversation c, Message m) {
                return fn.apply(c, m);
            }
        };
    }

    @Bean
    FlowHandler memberRelationStep() {
        return step(State.MEMBER_RELATION, (c, m) -> {
            String a = m.buttonAction();
            String rel = REL_CHILD.equals(a) ? "child" : REL_PARENT.equals(a) ? "parent" : REL_OTHER.equals(a) ? "other" : null;
            return rel == null ? again(c, "Please tap who you would like to add.")
                    : new Outcome(State.MEMBER_NAME, with(c, "memRel", rel), "What is their full name?");
        });
    }

    @Bean
    FlowHandler memberNameStep() {
        return step(State.MEMBER_NAME, (c, m) -> {
            String name = m.text() == null ? "" : m.text().trim().replaceAll("\\s+", " ");
            if (name.length() < 2 || name.length() > 120 || name.chars().anyMatch(ch -> Character.isDigit(ch))) {
                return again(c, "Please type their full name, using letters only.");
            }
            return new Outcome(State.MEMBER_DOB, with(c, "memName", name), "What is their date of birth? Please type it as DD-MM-YYYY.");
        });
    }

    @Bean
    FlowHandler memberDobStep() {
        return step(State.MEMBER_DOB, (c, m) -> {
            LocalDate dob = m.text() == null ? null : parseDob(m.text(), LocalDate.now(clock));
            if (dob == null) {
                return again(c, "That date does not look right. Please type the date of birth as DD-MM-YYYY, for example 07-03-2018.");
            }
            Map<String, String> ctx = with(c, "memDob", dob.toString());
            Conversation next = new Conversation(c.id(), c.tenantId(), c.channel(), c.contactHash(), State.MEMBER_DOB, ctx, c.version());
            if (minor(next)) {
                return askConsent(c, ctx);
            }
            return new Outcome(State.MEMBER_PHONE_CHOICE, ctx, "Do they have their own mobile phone?", buttonsFor(State.MEMBER_PHONE_CHOICE));
        });
    }

    @Bean
    FlowHandler memberPhoneChoiceStep() {
        return step(State.MEMBER_PHONE_CHOICE, (c, m) -> {
            if (PHONE_YES.equals(m.buttonAction())) {
                return new Outcome(State.MEMBER_PHONE, c.context(), "Please type their mobile number with the country code.");
            }
            if (PHONE_NO.equals(m.buttonAction())) {
                return askConsent(c, with(c));
            }
            return again(c, "Do they have their own mobile phone?");
        });
    }

    @Bean
    FlowHandler memberPhoneStep() {
        return step(State.MEMBER_PHONE, (c, m) -> {
            String digits = m.text() == null ? "" : m.text().replaceAll("[\\s()-]", "");
            if (!digits.matches("^\\+[0-9]{8,15}$")) {
                return again(c, "That number does not look right. Please type it again with the country code.");
            }
            return askConsent(c, with(c, "memPhone", digits));
        });
    }

    @Bean
    FlowHandler memberConsentStep() {
        return step(State.MEMBER_CONSENT, (c, m) -> {
            if (CONSENT_NO.equals(m.buttonAction())) {
                return done("Okay, nothing was saved.");
            }
            if (!CONSENT_YES.equals(m.buttonAction())) {
                return askConsent(c, with(c));
            }
            if (!minor(c)) {
                return submit(c, null);
            }
            byte[] raw = new byte[12];
            RANDOM.nextBytes(raw);
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            String code = String.format("%06d", RANDOM.nextInt(1_000_000));
            return new Outcome(State.MEMBER_OTP, with(c, "otpChal", challenge, "otpHash", hash(challenge, code), "otpTry", "0"),
                    "Your code is " + code + ". Please type it here to confirm your consent as parent or guardian.");
        });
    }

    @Bean
    FlowHandler memberOtpStep() {
        return step(State.MEMBER_OTP, (c, m) -> {
            Map<String, String> x = c.context();
            String typed = m.text() == null ? "" : m.text().trim();
            if (x.get("otpHash") != null && !x.get("otpHash").isEmpty() && java.security.MessageDigest.isEqual(
                    x.get("otpHash").getBytes(StandardCharsets.US_ASCII), hash(x.get("otpChal"), typed).getBytes(StandardCharsets.US_ASCII))) {
                return submit(c, x.get("otpChal"));
            }
            int tries = Integer.parseInt(x.getOrDefault("otpTry", "0")) + 1;
            if (tries >= OTP_TRIES) {
                return done("That code was not right too many times, so nothing was saved. You can start again from the menu.");
            }
            return new Outcome(State.MEMBER_OTP, with(c, "otpTry", String.valueOf(tries)), "That code is not right. Please try again.");
        });
    }
}
