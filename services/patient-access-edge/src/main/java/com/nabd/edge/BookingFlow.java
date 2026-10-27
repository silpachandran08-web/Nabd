package com.nabd.edge;

import com.nabd.edge.FlowHandler.Button;
import com.nabd.edge.FlowHandler.Conversation;
import com.nabd.edge.FlowHandler.Message;
import com.nabd.edge.FlowHandler.Outcome;
import com.nabd.edge.FlowHandler.State;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * ACC-040 + ACC-042: a returning patient books by tapping. Hi -> menu -> (who is this for, only when the phone has more than one
 * person) -> doctor (only when the clinic has more than one) -> day -> time -> confirm card -> Core. Book, who, day, time, confirm: five taps. Every step is a button or list row signed by ButtonTokens; free text is read only for the name and
 * date of birth of a child being added. The patient chooses a language once per conversation. Two unrecognised messages in a row
 * hand the thread to reception (state HANDOFF: the bot is silent until the thread is handed back). Phrases of the clinically signed
 * emergency list (edge.emergency-phrases, empty until signed) answer with the local emergency number whatever the state.
 * The shared sender with several branches (PLT-025) and token sessions are not part of this class: see the story report.
 */
@Configuration
@ConditionalOnProperty(name = "edge.booking-flow", havingValue = "true", matchIfMissing = true)
class BookingFlow {

    private static final int MISS_LIMIT = 2;
    private static final int ROWS = 10;
    private static final int TITLE = 20;

    private final CoreClient core;
    private final SlotHolds holds;
    private final AvailabilityProjection projection;
    private final EdgeProperties props;
    private final MeterRegistry meters;
    private final Clock clock;
    private final ContactLanguage languages;

    BookingFlow(CoreClient core, SlotHolds holds, AvailabilityProjection projection, EdgeProperties props, MeterRegistry meters, Clock clock,
                ContactLanguage languages) {
        this.languages = languages;
        this.core = core;
        this.holds = holds;
        this.projection = projection;
        this.props = props;
        this.meters = meters;
        this.clock = clock;
    }

    private record Step(State state, BookingFlow flow, BiFunction<Conversation, Message, Outcome> fn) implements FlowHandler {
        @Override
        public Outcome handle(Conversation c, Message m) {
            return flow.run(c, m, fn);
        }
    }

    @Bean FlowHandler bookingStart() { return new Step(State.START, this, this::start); }
    @Bean FlowHandler bookingDone() { return new Step(State.DONE, this, this::start); }
    @Bean FlowHandler bookingLanguage() { return new Step(State.LANGUAGE, this, this::language); }
    @Bean FlowHandler bookingMenu() { return new Step(State.MENU, this, this::menu); }
    @Bean FlowHandler bookingWho() { return new Step(State.WHO, this, this::who); }
    @Bean FlowHandler bookingChildName() { return new Step(State.CHILD_NAME, this, this::childName); }
    @Bean FlowHandler bookingChildDob() { return new Step(State.CHILD_DOB, this, this::childDob); }
    @Bean FlowHandler bookingChildSex() { return new Step(State.CHILD_SEX, this, this::childSex); }
    @Bean FlowHandler bookingDoctor() { return new Step(State.CHOOSING_DOCTOR, this, this::chooseDoctor); }
    @Bean FlowHandler bookingDay() { return new Step(State.CHOOSING_DAY, this, this::chooseDay); }
    @Bean FlowHandler bookingSlot() { return new Step(State.CHOOSING_SLOT, this, this::chooseSlot); }
    @Bean FlowHandler bookingConfirm() { return new Step(State.CONFIRMING, this, this::confirming); }
    @Bean FlowHandler bookingHandoff() { return new Step(State.HANDOFF, this, (c, m) -> new Outcome(State.HANDOFF, c.context(), null)); }

    // ---- the frame every step runs in ---------------------------------------------------------------------------------

    /** Emergency check first, then the step; a null answer is an unrecognised message (counted, second one in a row hands over). */
    Outcome run(Conversation c, Message m, BiFunction<Conversation, Message, Outcome> step) {
        String number = emergencyNumber(m.text());
        if (number != null) {
            Map<String, String> ctx = new HashMap<>(c.context());
            ctx.put("urgent", "1");
            meters.counter("edge_emergency_total").increment();
            return new Outcome(c.state(), ctx, BookingCopy.t(lang(ctx), "emergency", number));
        }
        Outcome o = step.apply(c, m);
        if (o == null) {
            return miss(c, null);
        }
        String before = c.context().get("misses"), after = o.context().get("misses");
        if (after != null && after.equals(before)) { // a valid step ends the run of unrecognised messages
            Map<String, String> ctx = new HashMap<>(o.context());
            ctx.remove("misses");
            return new Outcome(o.next(), ctx, o.reply(), o.buttons());
        }
        return o;
    }

    private String emergencyNumber(String text) {
        if (text == null || text.isBlank() || props.emergencyPhrases().isEmpty()) {
            return null;
        }
        String t = text.toLowerCase(Locale.ROOT);
        return props.emergencyPhrases().stream().anyMatch(p -> !p.isBlank() && t.contains(p.toLowerCase(Locale.ROOT)))
                ? props.emergencyNumbers().get(props.region().toLowerCase(Locale.ROOT)) : null;
    }

    private Outcome miss(Conversation c, String overrideKey) {
        int misses = Integer.parseInt(c.context().getOrDefault("misses", "0")) + 1;
        if (misses >= MISS_LIMIT) {
            meters.counter("edge_handoff_total", "reason", "unrecognised").increment();
            return handoff(c, "handoff");
        }
        Outcome again = prompt(c);
        Map<String, String> ctx = new HashMap<>(again.context());
        ctx.put("misses", String.valueOf(misses));
        return new Outcome(again.next(), ctx, overrideKey == null ? again.reply() : BookingCopy.t(lang(ctx), overrideKey), again.buttons());
    }

    private Outcome handoff(Conversation c, String key) {
        Map<String, String> ctx = new HashMap<>();
        ctx.put("lang", lang(c.context()));
        if (c.context().containsKey("urgent")) {
            ctx.put("urgent", "1");
        }
        return new Outcome(State.HANDOFF, ctx, BookingCopy.t(lang(ctx), key));
    }

    /** The question of the conversation's current step again (also what a returning patient sees: resume from the last step). */
    private Outcome prompt(Conversation c) {
        Map<String, String> ctx = new HashMap<>(c.context());
        ctx.remove("misses");
        return switch (c.state()) {
            case LANGUAGE -> askLanguage(c, ctx);
            case WHO -> askWho(c, ctx);
            case CHILD_NAME -> new Outcome(State.CHILD_NAME, ctx, BookingCopy.t(lang(ctx), "child.name"));
            case CHILD_DOB -> new Outcome(State.CHILD_DOB, ctx, BookingCopy.t(lang(ctx), "child.dob"));
            case CHILD_SEX -> askSex(ctx);
            case CHOOSING_DOCTOR -> askDoctor(c, ctx);
            case CHOOSING_DAY -> askDay(c, ctx);
            case CHOOSING_SLOT -> askSlots(c, ctx, "time.ask");
            case CONFIRMING -> askConfirm(c, ctx);
            case HANDOFF -> new Outcome(State.HANDOFF, ctx, null);
            default -> askMenu(c, ctx);
        };
    }

    // ---- language and menu --------------------------------------------------------------------------------------------

    private List<String> offered() {
        List<String> l = props.languages().getOrDefault(props.region().toLowerCase(Locale.ROOT), List.of("en")).stream().filter(BookingCopy::supported).toList();
        return l.isEmpty() ? List.of("en") : l;
    }

    private static String lang(Map<String, String> ctx) {
        return BookingCopy.supported(ctx.get("lang")) ? ctx.get("lang") : "en";
    }

    private Outcome start(Conversation c, Message m) {
        Map<String, String> ctx = new HashMap<>(c.context());
        ctx.remove("misses");
        if (ctx.get("lang") == null) {
            languages.find(c.tenantId(), c.channel(), c.contactHash()).filter(offered()::contains).ifPresent(l -> ctx.put("lang", l));
        }
        if (ctx.get("lang") == null && offered().size() == 1) {
            ctx.put("lang", offered().get(0));
        }
        return ctx.get("lang") == null ? askLanguage(c, ctx) : askMenu(c, ctx);
    }

    private Outcome askLanguage(Conversation c, Map<String, String> ctx) {
        return new Outcome(State.LANGUAGE, ctx, BookingCopy.t("en", "lang.ask") + (offered().contains("ar") ? " / " + BookingCopy.t("ar", "lang.ask") : ""),
                offered().stream().map(l -> new Button("lang:" + l, BookingCopy.NAMES.get(l))).toList());
    }

    private Outcome language(Conversation c, Message m) {
        String a = m.buttonAction();
        if (a == null || !a.startsWith("lang:") || !offered().contains(a.substring(5))) {
            return null;
        }
        Map<String, String> ctx = new HashMap<>(c.context());
        ctx.put("lang", a.substring(5));
        languages.save(c.tenantId(), c.channel(), c.contactHash(), a.substring(5));
        return askMenu(c, ctx);
    }

    private Outcome askMenu(Conversation c, Map<String, String> ctx) {
        String l = lang(ctx);
        // The context of a finished booking is dropped; only the language stays.
        Map<String, String> keep = new HashMap<>();
        keep.put("lang", l);
        return new Outcome(State.MENU, keep, BookingCopy.t(l, "menu", core.profile(c.tenantId()).clinicName()),
                List.of(new Button("menu:book", BookingCopy.t(l, "menu.book")), new Button("menu:mine", BookingCopy.t(l, "menu.mine")),
                        new Button("menu:talk", BookingCopy.t(l, "menu.talk"))));
    }

    private Outcome menu(Conversation c, Message m) {
        String a = m.buttonAction();
        if ("menu:book".equals(a)) {
            return beginBooking(c, new HashMap<>(c.context()));
        }
        if ("menu:talk".equals(a) || "menu:mine".equals(a)) { // "My appointment" belongs to ACC-041; reception answers until it lands
            meters.counter("edge_handoff_total", "reason", "requested").increment();
            return handoff(c, "handoff");
        }
        return null;
    }

    // ---- who is this for (ACC-042) -----------------------------------------------------------------------------------

    /** Me = the oldest adult on the phone (Core lists oldest first); children are the minors linked to a guardian. Other adults are never listed. */
    private record Family(CoreClient.Member me, List<CoreClient.Member> children) {
        static Family of(List<CoreClient.Member> members) {
            CoreClient.Member me = members.stream().filter(m -> m.years() >= 18).findFirst().orElse(members.isEmpty() ? null : members.get(0));
            List<CoreClient.Member> kids = members.stream().filter(m -> m != me && m.years() < 18 && "dependent".equals(m.relation())).limit(ROWS - 3).toList();
            return new Family(me, kids);
        }

        boolean lists(String ref) {
            return me != null && (me.patientRef().equals(ref) || children.stream().anyMatch(k -> k.patientRef().equals(ref)));
        }
    }

    private Outcome beginBooking(Conversation c, Map<String, String> ctx) {
        Family f = Family.of(core.household(c.tenantId(), c.contactHash()));
        if (f.me() == null) {
            meters.counter("edge_handoff_total", "reason", "unknown_phone").increment();
            return handoff(c, "unknown.phone");
        }
        return askWho(c, ctx);
    }

    private Outcome askWho(Conversation c, Map<String, String> ctx) {
        String l = lang(ctx);
        Family f = Family.of(core.household(c.tenantId(), c.contactHash()));
        if (f.me() == null) {
            return handoff(c, "unknown.phone");
        }
        List<Button> b = new ArrayList<>();
        b.add(new Button("who:" + f.me().patientRef(), BookingCopy.t(l, "who.me")));
        f.children().forEach(k -> b.add(new Button("who:" + k.patientRef(), cut(BookingCopy.t(l, "who.child", k.firstName(), k.years())))));
        b.add(new Button("who_add", BookingCopy.t(l, "who.add")));
        b.add(new Button("who_other", BookingCopy.t(l, "who.other")));
        return new Outcome(State.WHO, ctx, BookingCopy.t(l, "who.ask"), b);
    }

    private Outcome who(Conversation c, Message m) {
        String a = m.buttonAction();
        Map<String, String> ctx = new HashMap<>(c.context());
        if ("who_add".equals(a)) {
            return new Outcome(State.CHILD_NAME, ctx, BookingCopy.t(lang(ctx), "child.name"));
        }
        if ("who_other".equals(a)) {
            return new Outcome(State.MENU, ctx, BookingCopy.t(lang(ctx), "other.info"), List.of(new Button("menu:talk", BookingCopy.t(lang(ctx), "menu.talk"))));
        }
        if (a == null || !a.startsWith("who:")) {
            return null;
        }
        Family f = Family.of(core.household(c.tenantId(), c.contactHash()));
        String ref = a.substring(4);
        if (!f.lists(ref)) { // revoked or aged out since the list was shown: show the current one
            return askWho(c, ctx);
        }
        ctx.put("who", ref);
        ctx.put("firstName", f.me().patientRef().equals(ref) ? f.me().firstName() : f.children().stream().filter(k -> k.patientRef().equals(ref)).findFirst().orElseThrow().firstName());
        return afterWho(c, ctx);
    }

    private Outcome childName(Conversation c, Message m) {
        String t = m.buttonAction() == null && m.text() != null ? m.text().trim() : "";
        if (t.isEmpty()) {
            return null;
        }
        Map<String, String> ctx = new HashMap<>(c.context());
        String first = t.split("\\s+")[0];
        ctx.put("cname", first.length() > 60 ? first.substring(0, 60) : first);
        return new Outcome(State.CHILD_DOB, ctx, BookingCopy.t(lang(ctx), "child.dob"));
    }

    private static final List<DateTimeFormatter> DOB = List.of(DateTimeFormatter.ofPattern("d-M-uuuu"), DateTimeFormatter.ofPattern("d/M/uuuu"),
            DateTimeFormatter.ISO_LOCAL_DATE);

    private Outcome childDob(Conversation c, Message m) {
        String t = m.buttonAction() == null && m.text() != null ? m.text().trim() : "";
        if (t.isEmpty()) {
            return null;
        }
        for (DateTimeFormatter f : DOB) {
            try {
                LocalDate d = LocalDate.parse(t, f.withResolverStyle(java.time.format.ResolverStyle.STRICT));
                if (d.isAfter(LocalDate.now(clock))) {
                    break;
                }
                Map<String, String> ctx = new HashMap<>(c.context());
                ctx.put("dob", d.toString());
                return askSex(ctx);
            } catch (DateTimeParseException e) {
                // try the next format
            }
        }
        return miss(c, "child.dob.bad");
    }

    private Outcome askSex(Map<String, String> ctx) {
        String l = lang(ctx);
        return new Outcome(State.CHILD_SEX, ctx, BookingCopy.t(l, "child.sex", ctx.getOrDefault("cname", "")),
                List.of(new Button("sex:male", BookingCopy.t(l, "sex.male")), new Button("sex:female", BookingCopy.t(l, "sex.female")),
                        new Button("sex:other", BookingCopy.t(l, "sex.other"))));
    }

    private Outcome childSex(Conversation c, Message m) {
        String a = m.buttonAction();
        if (a == null || !List.of("sex:male", "sex:female", "sex:other").contains(a) || c.context().get("cname") == null || c.context().get("dob") == null) {
            return null;
        }
        Map<String, String> ctx = new HashMap<>(c.context());
        String ref;
        try {
            ref = core.addDependant(c.tenantId(), c.contactHash(), key(c, "child", ctx.get("cname"), ctx.get("dob")), ctx.get("cname"),
                    LocalDate.parse(ctx.get("dob")), a.substring(4));
        } catch (CoreClient.NotAMinor e) {
            ctx.remove("dob");
            return new Outcome(State.CHILD_DOB, ctx, BookingCopy.t(lang(ctx), "child.notminor"));
        }
        ctx.put("who", ref);
        ctx.put("firstName", ctx.remove("cname"));
        ctx.remove("dob");
        return afterWho(c, ctx);
    }

    // ---- doctor, day, time ---------------------------------------------------------------------------------------------

    private Outcome afterWho(Conversation c, Map<String, String> ctx) {
        List<CoreClient.Doctor> docs = core.profile(c.tenantId()).doctors();
        if (docs.isEmpty()) {
            return handoff(c, "day.none");
        }
        if (docs.size() == 1) {
            ctx.put("doctorId", docs.get(0).id().toString());
            return askDay(c, ctx);
        }
        return askDoctor(c, ctx);
    }

    private Outcome askDoctor(Conversation c, Map<String, String> ctx) {
        return new Outcome(State.CHOOSING_DOCTOR, ctx, BookingCopy.t(lang(ctx), "doc.ask"),
                core.profile(c.tenantId()).doctors().stream().limit(ROWS).map(d -> new Button("doc:" + d.id(), cut(d.name()))).toList());
    }

    private Outcome chooseDoctor(Conversation c, Message m) {
        String a = m.buttonAction();
        if (a == null || !a.startsWith("doc:") || core.profile(c.tenantId()).doctors().stream().noneMatch(d -> a.equals("doc:" + d.id()))) {
            return null;
        }
        Map<String, String> ctx = new HashMap<>(c.context());
        ctx.put("doctorId", a.substring(4));
        return askDay(c, ctx);
    }

    private Outcome askDay(Conversation c, Map<String, String> ctx) {
        String l = lang(ctx);
        ZoneId zone = ZoneId.of(core.profile(c.tenantId()).timeZone());
        LocalDate today = clock.instant().atZone(zone).toLocalDate();
        List<LocalDate> days = core.freeDays(c.tenantId(), UUID.fromString(ctx.get("doctorId")), today, today.plusDays(13)).stream().limit(3).toList();
        if (days.isEmpty()) {
            return handoff(c, "day.none");
        }
        DateTimeFormatter f = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.forLanguageTag(l));
        return new Outcome(State.CHOOSING_DAY, ctx, BookingCopy.t(l, "day.ask"), days.stream().map(d -> new Button("day:" + d, cut(f.format(d)))).toList());
    }

    private Outcome chooseDay(Conversation c, Message m) {
        String a = m.buttonAction();
        if (a == null || !a.startsWith("day:")) {
            return null;
        }
        Map<String, String> ctx = new HashMap<>(c.context());
        try {
            ctx.put("day", LocalDate.parse(a.substring(4)).toString());
        } catch (DateTimeParseException e) {
            return null;
        }
        return askSlots(c, ctx, "time.ask");
    }

    /** Free slots of the day as list rows (max 10). Nothing is held yet: the 2-minute hold starts when the patient picks one. */
    private List<Instant> freeSlots(Conversation c, Map<String, String> ctx) {
        UUID doctor = UUID.fromString(ctx.get("doctorId"));
        Instant now = clock.instant();
        var taken = holds.heldByOthers(c.tenantId(), doctor, c.id());
        return AvailabilityProjection.slotStarts(projection.read(c.tenantId(), doctor, LocalDate.parse(ctx.get("day")))).stream()
                .filter(s -> s.isAfter(now) && !taken.contains(s)).sorted().distinct().toList();
    }

    private Outcome askSlots(Conversation c, Map<String, String> ctx, String key) {
        String l = lang(ctx);
        List<Instant> free = freeSlots(c, ctx);
        if (free.isEmpty()) {
            Outcome days = askDay(c, ctx);
            return new Outcome(days.next(), days.context(), BookingCopy.t(l, "time.none"), days.buttons());
        }
        return new Outcome(State.CHOOSING_SLOT, ctx, BookingCopy.t(l, key, doctorName(c, ctx), dateLabel(c, ctx, LocalDate.parse(ctx.get("day")))), slotButtons(c, ctx, free.stream().limit(ROWS).toList()));
    }

    private List<Button> slotButtons(Conversation c, Map<String, String> ctx, List<Instant> slots) {
        DateTimeFormatter f = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.forLanguageTag(lang(ctx))).withZone(zone(c));
        return slots.stream().map(s -> new Button("slot:" + s.getEpochSecond(), cut(f.format(s)))).toList();
    }

    private Outcome chooseSlot(Conversation c, Message m) {
        String a = m.buttonAction();
        if (a == null || !a.startsWith("slot:")) {
            return null;
        }
        Instant slot;
        try {
            slot = Instant.ofEpochSecond(Long.parseLong(a.substring(5)));
        } catch (NumberFormatException e) {
            return null;
        }
        Map<String, String> ctx = new HashMap<>(c.context());
        UUID doctor = UUID.fromString(ctx.get("doctorId"));
        holds.release(c.tenantId(), c.id()); // the previous pick, if any
        if (!freeSlots(c, ctx).contains(slot) || !holds.tryHold(c.tenantId(), doctor, slot, c.id(), props.pickHoldSeconds())) {
            return alternatives(c, ctx, slot);
        }
        ctx.put("slotStart", slot.toString());
        return askConfirm(c, ctx);
    }

    /** The slot was lost between offer and pick/confirm: say so and give the 3 nearest free ones in the same message. */
    private Outcome alternatives(Conversation c, Map<String, String> ctx, Instant around) {
        ctx.remove("slotStart");
        List<Instant> near = freeSlots(c, ctx).stream().filter(s -> !s.equals(around))
                .sorted(Comparator.comparingLong((Instant s) -> Math.abs(Duration.between(around, s).toSeconds()))).limit(3).sorted().toList();
        if (near.isEmpty()) {
            Outcome days = askDay(c, ctx);
            return new Outcome(days.next(), days.context(), BookingCopy.t(lang(ctx), "taken") + "\n" + days.reply(), days.buttons());
        }
        return new Outcome(State.CHOOSING_SLOT, ctx, BookingCopy.t(lang(ctx), "taken"), slotButtons(c, ctx, near));
    }

    // ---- confirm and book ---------------------------------------------------------------------------------------------

    private Outcome askConfirm(Conversation c, Map<String, String> ctx) {
        String l = lang(ctx);
        Instant slot = Instant.parse(ctx.get("slotStart"));
        return new Outcome(State.CONFIRMING, ctx, BookingCopy.t(l, "card", doctorName(c, ctx), dateLabel(c, ctx, slot), timeLabel(c, ctx, slot), core.profile(c.tenantId()).clinicName()),
                List.of(new Button("confirm", BookingCopy.t(l, "confirm")), new Button("change", BookingCopy.t(l, "change"))));
    }

    private Outcome confirming(Conversation c, Message m) {
        Map<String, String> ctx = new HashMap<>(c.context());
        if ("change".equals(m.buttonAction())) {
            holds.release(c.tenantId(), c.id());
            ctx.remove("slotStart");
            return askSlots(c, ctx, "time.ask");
        }
        if (!"confirm".equals(m.buttonAction()) || ctx.get("slotStart") == null) {
            return null;
        }
        String l = lang(ctx);
        if (!Family.of(core.household(c.tenantId(), c.contactHash())).lists(ctx.get("who"))) { // access ended since the choice was made
            holds.release(c.tenantId(), c.id());
            return beginBooking(c, ctx);
        }
        UUID doctor = UUID.fromString(ctx.get("doctorId"));
        Instant slot = Instant.parse(ctx.get("slotStart"));
        // One key per (conversation, doctor, slot, person): a second tap, a retry after a timeout or a re-run all replay the first answer.
        String key = key(c, "book", doctor.toString(), slot.toString(), ctx.get("who"));
        List<String> flags = ctx.containsKey("urgent") ? List.of("red_flag") : List.of();
        CoreClient.Booking b;
        try {
            b = book(c, key, ctx.get("who"), doctor, slot, flags);
        } catch (CoreClient.SlotTaken e) {
            holds.release(c.tenantId(), c.id());
            return alternatives(c, ctx, slot);
        } catch (Http.UpstreamStatusException e) {
            if (e.status / 100 == 4 && e.status != 409 && e.status != 429) { // Core refused for a rule we cannot fix here
                holds.release(c.tenantId(), c.id());
                return handoff(c, "fail");
            }
            return checking(c, ctx);
        } catch (IllegalStateException e) { // timeout or outage, also after the retry: the answer is unknown, so say nothing about the result
            return checking(c, ctx);
        }
        holds.release(c.tenantId(), c.id());
        Map<String, String> done = new HashMap<>();
        done.put("lang", l);
        String when = dateLabel(c, ctx, slot), at = timeLabel(c, ctx, slot);
        if ("CONFIRMED".equals(b.status())) {
            return new Outcome(State.DONE, done, BookingCopy.t(l, "done.confirmed", doctorName(c, ctx), when, at, core.profile(c.tenantId()).clinicName()));
        }
        long minutes = b.slaDueAt() == null ? 10 : Math.max(1, (Duration.between(clock.instant(), b.slaDueAt()).getSeconds() + 59) / 60);
        return new Outcome(State.DONE, done, BookingCopy.t(l, "done.requested", doctorName(c, ctx), when, at, minutes));
    }

    /** A timeout is not a refusal: ask Core again with the same Idempotency-Key (it answers from the stored result) before saying anything. */
    private CoreClient.Booking book(Conversation c, String key, String who, UUID doctor, Instant slot, List<String> flags) {
        try {
            return core.book(c.tenantId(), key, who, doctor, slot, flags);
        } catch (Http.UpstreamStatusException | CoreClient.SlotTaken e) {
            throw e;
        } catch (IllegalStateException first) {
            meters.counter("edge_book_retry_total").increment();
            return core.book(c.tenantId(), key, who, doctor, slot, flags);
        }
    }

    private Outcome checking(Conversation c, Map<String, String> ctx) {
        meters.counter("edge_book_unknown_total").increment();
        String l = lang(ctx);
        return new Outcome(State.CONFIRMING, ctx, BookingCopy.t(l, "checking"), // the same Confirm tap asks again with the same key
                List.of(new Button("confirm", BookingCopy.t(l, "confirm")), new Button("change", BookingCopy.t(l, "change"))));
    }

    // ---- small helpers ------------------------------------------------------------------------------------------------

    private ZoneId zone(Conversation c) {
        return ZoneId.of(core.profile(c.tenantId()).timeZone());
    }

    private String doctorName(Conversation c, Map<String, String> ctx) {
        return core.profile(c.tenantId()).doctors().stream().filter(d -> d.id().toString().equals(ctx.get("doctorId"))).map(CoreClient.Doctor::name).findFirst().orElse("");
    }

    private String dateLabel(Conversation c, Map<String, String> ctx, Instant at) {
        return dateLabel(c, ctx, at.atZone(zone(c)).toLocalDate());
    }

    private String dateLabel(Conversation c, Map<String, String> ctx, LocalDate d) {
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.forLanguageTag(lang(ctx))).format(d);
    }

    private String timeLabel(Conversation c, Map<String, String> ctx, Instant at) {
        return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.forLanguageTag(lang(ctx))).withZone(zone(c)).format(at);
    }

    private static String cut(String s) {
        return s.length() <= TITLE ? s : s.substring(0, TITLE);
    }

    private static String key(Conversation c, String... parts) {
        return UUID.nameUUIDFromBytes((c.id() + ":" + String.join(":", parts)).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
