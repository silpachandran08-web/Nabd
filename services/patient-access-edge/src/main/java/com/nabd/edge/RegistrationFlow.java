package com.nabd.edge;

import com.nabd.edge.FlowHandler.Button;
import com.nabd.edge.FlowHandler.Conversation;
import com.nabd.edge.FlowHandler.Message;
import com.nabd.edge.FlowHandler.Outcome;
import com.nabd.edge.FlowHandler.State;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * ACC-043 (PAT-01): an unknown number's first message. Order is fixed: privacy notice and consent (ConsentFlow, step=reg), then name, sex,
 * date of birth or age, then Core creates the patient and the chat moves on to booking. Also the desk's "Complete in chat": address,
 * then language, written back to the record. The date of birth and the address are sent to Core in the step that receives them and are
 * never kept in the Edge context. Copy is English; hi / kn / te fall back to it until a native-speaker review (needs external action).
 */
@Component
public class RegistrationFlow {

    static final String STEP = "reg";
    static final String SEX_PREFIX = "reg_sex_";
    static final String LANG_PREFIX = "reg_lang_";
    private static final Pattern DOB = Pattern.compile("(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4})");
    private static final Pattern AGE = Pattern.compile("\\d{1,3}");
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("d-M-uuuu").withResolverStyle(ResolverStyle.STRICT);

    private final ConsentFlow consent;
    private final CoreClient core;
    private final CommsClient comms;
    private final Clock clock;

    RegistrationFlow(ConsentFlow consent, CoreClient core, CommsClient comms, Clock clock) {
        this.consent = consent;
        this.core = core;
        this.comms = comms;
        this.clock = clock;
    }

    /** For the booking flow (ACC-040): an unknown number starts here. Shows the notice and asks for consent before any health question. */
    public Outcome enter(Conversation c, String language) {
        return consent.begin(c, language, true);
    }

    static Outcome declined() {
        return new Outcome(State.DONE, Map.of(), "No problem. We have not created a record for you. You can book by phone with the clinic at any time, or tap below to talk to the clinic.",
                List.of(new Button(ConsentFlow.CALLBACK, "Talk to clinic")));
    }

    static Outcome afterConsent(String lang, String consentId) {
        Map<String, String> ctx = new HashMap<>();
        ctx.put("lang", lang);
        ctx.put("step", STEP);
        ctx.put("consentId", consentId);
        return new Outcome(State.REG_NAME, ctx, "Thank you. What is your name?");
    }

    private static Outcome talkToClinic(String reply) {
        return new Outcome(State.DONE, Map.of(), reply, List.of(new Button(ConsentFlow.CALLBACK, "Talk to clinic")));
    }

    /** Before any booking handler (a handler with the default order replaces this one): the desk's pending completion, else registration. */
    @Component
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class Start implements FlowHandler {
        private final RegistrationFlow flow;

        Start(RegistrationFlow flow) {
            this.flow = flow;
        }

        @Override
        public State state() {
            return State.START;
        }

        @Override
        public Outcome handle(Conversation c, Message m) {
            CoreClient.Pending p = flow.core.pendingCompletion(c.tenantId(), c.contactHash());
            if (p == null) {
                return flow.enter(c, null);
            }
            Map<String, String> ctx = new HashMap<>();
            ctx.put("patientRef", p.patientRef());
            ctx.put("firstName", p.firstName());
            return new Outcome(State.COMPLETE_ADDRESS, ctx, "Hello " + p.firstName() + ", the clinic asked us to finish your registration. What is your address?");
        }
    }

    @Component
    static class Name implements FlowHandler {
        public State state() {
            return State.REG_NAME;
        }

        public Outcome handle(Conversation c, Message m) {
            String t = m.text() == null ? "" : m.text().strip();
            if (!"text".equals(m.kind()) || t.isEmpty() || t.length() > 80) {
                return new Outcome(State.REG_NAME, c.context(), "Please type your name.");
            }
            Map<String, String> ctx = new HashMap<>(c.context());
            ctx.put("firstName", t);
            return new Outcome(State.REG_SEX, ctx, "Thank you. Please choose:", List.of(new Button(SEX_PREFIX + "male", "Male"),
                    new Button(SEX_PREFIX + "female", "Female"), new Button(SEX_PREFIX + "other", "Other")));
        }
    }

    @Component
    static class Sex implements FlowHandler {
        public State state() {
            return State.REG_SEX;
        }

        public Outcome handle(Conversation c, Message m) {
            String a = m.buttonAction();
            if (a == null || !a.startsWith(SEX_PREFIX) || !List.of("male", "female", "other").contains(a.substring(SEX_PREFIX.length()))) {
                return new Outcome(State.REG_SEX, c.context(), "Please tap one of the buttons.", List.of(new Button(SEX_PREFIX + "male", "Male"),
                        new Button(SEX_PREFIX + "female", "Female"), new Button(SEX_PREFIX + "other", "Other")));
            }
            Map<String, String> ctx = new HashMap<>(c.context());
            ctx.put("sex", a.substring(SEX_PREFIX.length()));
            return new Outcome(State.REG_DOB, ctx, "What is your date of birth (like 25-03-1990)? If you prefer, type your age in years.");
        }
    }

    @Component
    static class Dob implements FlowHandler {
        private final RegistrationFlow flow;

        Dob(RegistrationFlow flow) {
            this.flow = flow;
        }

        public State state() {
            return State.REG_DOB;
        }

        public Outcome handle(Conversation c, Message m) {
            String t = m.text() == null ? "" : m.text().strip();
            LocalDate today = LocalDate.now(flow.clock);
            LocalDate dob = null;
            Integer age = null;
            java.util.regex.Matcher d = DOB.matcher(t);
            if (d.matches()) {
                try {
                    dob = LocalDate.parse(Integer.parseInt(d.group(1)) + "-" + Integer.parseInt(d.group(2)) + "-" + d.group(3), DMY);
                } catch (DateTimeException e) {
                    dob = null;
                }
                if (dob != null && (dob.isAfter(today) || Period.between(dob, today).getYears() > 120)) {
                    dob = null;
                }
                if (dob != null) {
                    age = Period.between(dob, today).getYears();
                }
            } else if (AGE.matcher(t).matches() && Integer.parseInt(t) <= 120) {
                age = Integer.parseInt(t);
            }
            if (age == null) {
                return new Outcome(State.REG_DOB, c.context(), "Sorry, I could not read that. Please type a date like 25-03-1990, or your age in years.");
            }
            if (age < 18) { // a minor is registered by a guardian: no patient, no booking from this chat
                return talkToClinic("Patients under 18 are registered by a parent or guardian. Please ask them to message us from their own number, or tap below to talk to the clinic.");
            }
            String phone = flow.comms.verifiedPhone(c.tenantId(), c.contactHash());
            if (phone == null) {
                return talkToClinic("We could not confirm your number just now. Please tap below to talk to the clinic.");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("phoneVerified", true);
            body.put("phone", phone);
            body.put("phoneHash", c.contactHash());
            body.put("firstName", c.context().get("firstName"));
            if (dob != null) {
                body.put("dob", dob.toString());
            } else {
                body.put("age", age);
            }
            body.put("sex", c.context().get("sex"));
            body.put("consentRecordId", c.context().get("consentId"));
            CoreClient.Created r = flow.core.createPatient(c.tenantId(), body, "reg-" + m.messageId());
            if (r.patientRef() == null) { // a likely duplicate (the desk sees the suggestion) or a refusal: nothing was created
                return talkToClinic("We could not complete your registration here. Please tap below and the clinic will help you.");
            }
            Map<String, String> ctx = new HashMap<>();
            ctx.put("lang", c.context().getOrDefault("lang", ConsentCopy.DEFAULT));
            ctx.put("patientRef", r.patientRef());
            ctx.put("firstName", c.context().get("firstName"));
            return new Outcome(State.CHOOSING_DOCTOR, ctx, "You are registered, " + c.context().get("firstName") + ". Let us book your visit.");
        }
    }

    @Component
    static class Address implements FlowHandler {
        private final RegistrationFlow flow;

        Address(RegistrationFlow flow) {
            this.flow = flow;
        }

        public State state() {
            return State.COMPLETE_ADDRESS;
        }

        public Outcome handle(Conversation c, Message m) {
            String t = m.text() == null ? "" : m.text().strip();
            if (!"text".equals(m.kind()) || t.isEmpty() || t.length() > 300) {
                return new Outcome(State.COMPLETE_ADDRESS, c.context(), "Please type your address.");
            }
            flow.core.completePatient(c.tenantId(), c.context().get("patientRef"), Map.of("address", t), "complete-addr-" + m.messageId());
            return new Outcome(State.COMPLETE_LANG, c.context(), "Thank you. Which language should we use with you?", List.of(new Button(LANG_PREFIX + "en", "English"),
                    new Button(LANG_PREFIX + "hi", "Hindi"), new Button(LANG_PREFIX + "kn", "Kannada")));
        }
    }

    @Component
    static class Lang implements FlowHandler {
        private final RegistrationFlow flow;

        Lang(RegistrationFlow flow) {
            this.flow = flow;
        }

        public State state() {
            return State.COMPLETE_LANG;
        }

        public Outcome handle(Conversation c, Message m) {
            String a = m.buttonAction();
            if (a == null || !a.startsWith(LANG_PREFIX) || !ConsentCopy.supported(a.substring(LANG_PREFIX.length()))) {
                return new Outcome(State.COMPLETE_LANG, c.context(), "Please tap one of the buttons.", List.of(new Button(LANG_PREFIX + "en", "English"),
                        new Button(LANG_PREFIX + "hi", "Hindi"), new Button(LANG_PREFIX + "kn", "Kannada")));
            }
            flow.core.completePatient(c.tenantId(), c.context().get("patientRef"), Map.of("preferredLanguage", a.substring(LANG_PREFIX.length())), "complete-lang-" + m.messageId());
            return new Outcome(State.DONE, Map.of(), "Thank you. Your registration is complete.");
        }
    }
}
