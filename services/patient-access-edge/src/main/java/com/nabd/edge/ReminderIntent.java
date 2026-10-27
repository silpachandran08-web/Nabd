package com.nabd.edge;

import com.nabd.edge.CoreClient.ActResult;
import com.nabd.edge.CoreClient.ActStatus;
import com.nabd.edge.FlowHandler.Button;
import com.nabd.edge.FlowHandler.Conversation;
import com.nabd.edge.FlowHandler.Message;
import com.nabd.edge.FlowHandler.Outcome;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ACC-041: the buttons of the T-24 h / T-2 h reminders (COM-043 issues them with these action ids, signed with the shared
 * button key): {@code confirm:<appointment id, 32 hex>}, {@code resched:<id>}, {@code cancel:<id>}, {@code callme:<id>}; the follow-up
 * taps are {@code slot:<epoch seconds>}, {@code why:<reason code>} and {@code travel:<minutes>}. The flow state lives in the signed
 * button, the conversation context (appointmentId, patientRef) and Core, so a tap works whatever step the conversation was in.
 * Every Core change is made by the patient's own patientRef (Core checks ownership) and is keyed by the message id (replay-safe).
 * English copy only (shortcut: the other languages come with the template catalogue, COM-020).
 */
@Component
public class ReminderIntent implements Intent {

    /** shortcut: ACC-007 owns the coded cancel-reason list (read through scheduling.api); these three stand in until it is merged. */
    static final List<Button> REASONS = List.of(new Button("why:cant_make_it", "Can't make it"), new Button("why:feeling_better", "Feeling better"),
            new Button("why:other", "Other reason"));
    private static final String TRAVEL_Q = "How long does it take you to get to the clinic? Tap 10, 20 or 30 minutes, or reply 45 or 60.";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH);

    private final CoreClient core;
    private final TravelBuffers travel;
    private final EtaNotifications notifications;
    private final Clock clock;

    ReminderIntent(CoreClient core, TravelBuffers travel, EtaNotifications notifications, Clock clock) {
        this.core = core;
        this.travel = travel;
        this.notifications = notifications;
        this.clock = clock;
    }

    /** The action id for a reminder button of this appointment (COM-043 and the booking flow use it). */
    public static String action(String verb, UUID appointmentId) {
        return verb + ":" + appointmentId.toString().replace("-", "");
    }

    /** The first-booking question; the booking flow (ACC-040) returns this once, when {@link #needsTravelTime} is true. */
    public static Outcome askTravel(Conversation c, String patientRef, String lead) {
        Map<String, String> ctx = new HashMap<>(c.context());
        ctx.put("patientRef", patientRef);
        ctx.put("flow", "travel");
        return new Outcome(c.state(), ctx, (lead == null ? "" : lead + " ") + TRAVEL_Q,
                List.of(new Button("travel:10", "10 min"), new Button("travel:20", "20 min"), new Button("travel:30", "30 min")));
    }

    public boolean needsTravelTime(UUID tenantId, String patientRef) {
        return travel.get(tenantId, patientRef).isEmpty();
    }

    @Override
    public Optional<Outcome> handle(Conversation c, Message m) {
        String a = m.buttonAction();
        if (a == null) {
            return textAnswer(c, m.text());
        }
        int colon = a.indexOf(':');
        if (colon < 0) {
            return Optional.empty();
        }
        String verb = a.substring(0, colon), arg = a.substring(colon + 1);
        return switch (verb) {
            case "confirm" -> appointment(arg).map(id -> confirm(c, id, m));
            case "resched" -> appointment(arg).map(id -> offerSlots(c, id, "Pick a new time."));
            case "cancel" -> appointment(arg).map(id -> startCancel(c, id));
            case "callme" -> appointment(arg).map(id -> callMe(c, id, m));
            case "why" -> Optional.of(cancel(c, arg, m));
            case "slot" -> Optional.of(reschedule(c, arg, m));
            case "travel" -> Optional.of("change".equals(arg) ? askTravel(c, firstRef(c), null) : setTravel(c, arg));
            default -> Optional.empty();
        };
    }

    // ---- steps ---------------------------------------------------------------------------------------------------------

    private Outcome confirm(Conversation c, UUID id, Message m) {
        Owned o = owner(c, id);
        if (o == null) {
            return say(c, id, null, "We could not find that appointment for you. Please call the clinic.");
        }
        ActResult r = core.act(c.tenantId(), id, "confirm", o.ref, Map.of(), m.messageId());
        if (r.outcome() != ActStatus.OK) {
            return say(c, id, o.ref, "That appointment can no longer be confirmed here. Please call the clinic.");
        }
        notifications.track(c.tenantId(), id.toString(), c.contactHash(), o.ref);
        String done = "Thank you, your appointment is confirmed.";
        return travel.get(c.tenantId(), o.ref).isPresent() ? say(c, id, o.ref, done) : withAppointment(askTravel(c, o.ref, done), id);
    }

    private Outcome startCancel(Conversation c, UUID id) {
        Owned o = owner(c, id);
        if (o == null) {
            return say(c, id, null, "We could not find that appointment for you. Please call the clinic.");
        }
        if (insideWindow(o.detail)) {
            return window(c, id, o.ref, o.detail.windowHours());
        }
        return new Outcome(c.state(), ctx(c, id, o.ref), "Why are you cancelling? One tap is enough.", REASONS);
    }

    private Outcome cancel(Conversation c, String reason, Message m) {
        UUID id = contextAppointment(c);
        String ref = c.context().get("patientRef");
        if (id == null || ref == null || REASONS.stream().noneMatch(b -> b.action().equals("why:" + reason))) {
            return say(c, id, ref, "Please start again from your reminder message.");
        }
        ActResult r = core.act(c.tenantId(), id, "cancel", ref, Map.of("reasonCode", reason), m.messageId());
        return switch (r.outcome()) {
            case OK -> say(c, id, ref, "Your appointment is cancelled. We have released the slot. You can book again any time.");
            case INSIDE_WINDOW -> window(c, id, ref, r.windowHours());
            default -> say(c, id, ref, "That appointment can no longer be cancelled here. Please call the clinic.");
        };
    }

    private Outcome offerSlots(Conversation c, UUID id, String lead) {
        Owned o = owner(c, id);
        if (o == null) {
            return say(c, id, null, "We could not find that appointment for you. Please call the clinic.");
        }
        if (insideWindow(o.detail)) {
            return window(c, id, o.ref, o.detail.windowHours());
        }
        ZoneId zone = ZoneId.of(o.detail.zone());
        Instant now = clock.instant();
        LocalDate from = now.atZone(zone).toLocalDate();
        List<Button> buttons = new ArrayList<>();
        for (int d = 0; d < 3 && buttons.size() < 3; d++) {
            for (var session : core.doctorDay(c.tenantId(), o.detail.doctorId(), from.plusDays(d))) {
                for (var slot : session.path("slots")) {
                    Instant at = Instant.parse(slot.asString(""));
                    Button b = new Button("slot:" + at.getEpochSecond(), WHEN.format(at.atZone(zone)));
                    if (buttons.size() < 3 && at.isAfter(now) && !at.equals(o.detail.startTime()) && !buttons.contains(b)) {
                        buttons.add(b);
                    }
                }
            }
        }
        if (buttons.isEmpty()) {
            return new Outcome(c.state(), ctx(c, id, o.ref), "There are no free times in the next 3 days. Tap Call me and the desk will help.",
                    List.of(new Button(action("callme", id), "Call me")));
        }
        return new Outcome(c.state(), ctx(c, id, o.ref), lead, buttons);
    }

    private Outcome reschedule(Conversation c, String epoch, Message m) {
        UUID id = contextAppointment(c);
        String ref = c.context().get("patientRef");
        if (id == null || ref == null || !epoch.matches("\\d{9,11}")) {
            return say(c, id, ref, "Please start again from your reminder message.");
        }
        Instant at = Instant.ofEpochSecond(Long.parseLong(epoch));
        ActResult r = core.act(c.tenantId(), id, "reschedule", ref, Map.of("newStartTime", at.toString()), m.messageId());
        switch (r.outcome()) {
            case OK -> {
                notifications.untrack(c.tenantId(), id.toString());
                Outcome done = say(c, r.appointmentId(), ref, "Done. Your new time is " + WHEN.format(at.atZone(zoneOf(c, r.appointmentId(), ref))) + ". The old slot is released.");
                notifications.track(c.tenantId(), r.appointmentId().toString(), c.contactHash(), ref);
                return done;
            }
            case CONFLICT -> {
                return offerSlots(c, id, "That time was just taken. Pick another.");
            }
            case INSIDE_WINDOW -> {
                return window(c, id, ref, r.windowHours());
            }
            default -> {
                return say(c, id, ref, "That appointment can no longer be moved here. Please call the clinic.");
            }
        }
    }

    private Outcome callMe(Conversation c, UUID id, Message m) {
        Owned o = owner(c, id);
        if (o == null || core.act(c.tenantId(), id, "call-me", o.ref, Map.of(), m.messageId()).outcome() != ActStatus.OK) {
            return say(c, id, null, "We could not pass that on. Please call the clinic.");
        }
        return say(c, id, o.ref, "Thank you. The clinic desk will call you.");
    }

    private Outcome setTravel(Conversation c, String minutes) {
        String ref = c.context().get("patientRef") != null ? c.context().get("patientRef") : firstRef(c);
        if (ref == null || !minutes.matches("\\d{2}") || !TravelBuffers.ALLOWED.contains(Integer.parseInt(minutes))) {
            return say(c, null, ref, "Please tap 10, 20 or 30 minutes, or reply 45 or 60.");
        }
        travel.put(c.tenantId(), ref, Integer.parseInt(minutes));
        return say(c, contextAppointment(c), ref, "Thank you. We will tell you when to leave, allowing " + minutes + " minutes. Send \"change travel time\" to change it.");
    }

    /** Free text: a number while the travel question is open, or "change travel time". */
    private Optional<Outcome> textAnswer(Conversation c, String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if ("travel".equals(c.context().get("flow")) && t.matches("\\d{2}")) {
            return Optional.of(setTravel(c, t));
        }
        if (t.matches("(change|update) (my )?travel( time)?")) {
            String ref = firstRef(c);
            return Optional.of(ref == null ? say(c, null, null, "We could not find your record. Please call the clinic.") : askTravel(c, ref, null));
        }
        return Optional.empty();
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    private record Owned(String ref, CoreClient.Detail detail) {
    }

    /** The household member who owns the appointment (Core answers 403 for the others), with the appointment's details. */
    private Owned owner(Conversation c, UUID id) {
        for (String ref : core.household(c.tenantId(), c.contactHash())) {
            CoreClient.Detail d = core.detail(c.tenantId(), id, ref);
            if (d != null) {
                return new Owned(ref, d);
            }
        }
        return null;
    }

    private String firstRef(Conversation c) {
        return core.household(c.tenantId(), c.contactHash()).stream().findFirst().orElse(null);
    }

    private ZoneId zoneOf(Conversation c, UUID id, String ref) {
        CoreClient.Detail d = core.detail(c.tenantId(), id, ref);
        return d == null ? ZoneId.of("UTC") : ZoneId.of(d.zone());
    }

    private boolean insideWindow(CoreClient.Detail d) {
        return d.windowHours() > 0 && d.startTime().isBefore(clock.instant().plusSeconds(d.windowHours() * 3600));
    }

    private Outcome window(Conversation c, UUID id, String ref, long hours) {
        return new Outcome(c.state(), ctx(c, id, ref), "This appointment is within " + hours + " hours, so it cannot be changed here. "
                + "Tap Call me and the clinic desk will ring you.", List.of(new Button(action("callme", id), "Call me")));
    }

    private static Optional<UUID> appointment(String hex) {
        if (!hex.matches("[0-9a-f]{32}")) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(hex.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5")));
    }

    private static UUID contextAppointment(Conversation c) {
        String v = c.context().get("appointmentId");
        return v == null ? null : UUID.fromString(v);
    }

    private static Map<String, String> ctx(Conversation c, UUID id, String ref) {
        Map<String, String> m = new HashMap<>(c.context());
        m.remove("flow");
        if (id != null) {
            m.put("appointmentId", id.toString());
        }
        if (ref != null) {
            m.put("patientRef", ref);
        }
        return m;
    }

    private static Outcome say(Conversation c, UUID id, String ref, String text) {
        return new Outcome(c.state(), ctx(c, id, ref), text);
    }

    private static Outcome withAppointment(Outcome o, UUID id) {
        Map<String, String> m = new HashMap<>(o.context());
        m.put("appointmentId", id.toString());
        return new Outcome(o.next(), m, o.reply(), o.buttons());
    }
}
