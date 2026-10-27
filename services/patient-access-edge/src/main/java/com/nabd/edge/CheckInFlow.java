package com.nabd.edge;

import com.nabd.edge.CoreClient.Candidate;
import com.nabd.edge.CoreClient.CheckInResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ACC-033: self check-in in WhatsApp. Starts from the door QR (a wa.me link that prefills "CHECKIN <code>") or from the "I'm here"
 * button of the T-2 h reminder, whatever state the conversation is in. The rules (arrived status, date check, token, geofence, one
 * token per appointment) are all Core's: this flow only finds who can check in, asks which family member (one tap) and for a
 * location when the clinic requires it, and passes the answers on with an Idempotency-Key, so a re-run of the step repeats nothing.
 * The shared location lives in memory for one Core call: it is never put in the conversation context, the database or a log.
 * Assumed Comms shape (not in the Comms spec yet): a shared location arrives as kind "location" with text "lat,lng[,accuracyM]".
 * shortcut: English copy only, the pick offers at most two named buttons plus "All of us" (WhatsApp allows 3 reply buttons).
 */
@Component
public class CheckInFlow implements FlowHandler {

    static final String IM_HERE = "im_here";
    static final String ALL = "checkin_all";
    static final String PICK = "checkin_pick_";
    private static final Pattern CODE = Pattern.compile("(?i)^\\s*checkin\\s+([A-Z0-9]{6,12})\\s*$");
    private static final int TITLE_MAX = 20;

    private final CoreClient core;

    CheckInFlow(CoreClient core) {
        this.core = core;
    }

    /** True for "CHECKIN <code>" text and for the "I'm here" button. */
    static boolean claims(Message m) {
        return IM_HERE.equals(m.buttonAction()) || "text".equals(m.kind()) && m.text() != null && CODE.matcher(m.text()).matches();
    }

    @Override
    public State state() {
        return State.CHECKIN;
    }

    @Override
    public Outcome handle(Conversation c, Message m) {
        if (claims(m)) {
            return begin(c, m);
        }
        String step = c.context().get("step");
        if ("pick".equals(step)) {
            return pick(c, m);
        }
        if ("loc".equals(step)) {
            return location(c, m);
        }
        return done("No check-in is waiting. Scan the code at the door, or ask the front desk.");
    }

    private Outcome begin(Conversation c, Message m) {
        boolean viaQr = !IM_HERE.equals(m.buttonAction());
        CoreClient.CheckInContext ctx = core.checkInContext(c.tenantId(), c.contactHash());
        if (viaQr) {
            Matcher code = CODE.matcher(m.text());
            if (!code.matches() || !code.group(1).equalsIgnoreCase(ctx.facilityCode())) {
                return done("That check-in code is not for this clinic. Please scan the poster at the door again.");
            }
        }
        if (ctx.candidates().isEmpty()) {
            return done("We could not find an appointment for you today. Please tell the front desk and they will help.");
        }
        List<Candidate> pending = ctx.candidates().stream().filter(x -> !x.checkedIn()).toList();
        String channel = (viaQr ? "qr" : "whatsapp") + (ctx.geofenceRequired() ? ":geo" : "");
        if (pending.size() > 1) {
            Map<String, String> next = new HashMap<>(c.context());
            next.put("step", "pick");
            next.put("cands", encode(pending));
            next.put("ciChannel", channel);
            List<Button> buttons = new ArrayList<>();
            for (int i = 0; i < Math.min(2, pending.size()); i++) {
                buttons.add(new Button(PICK + i, title(pending.get(i).firstName())));
            }
            buttons.add(new Button(ALL, "All of us"));
            return new Outcome(State.CHECKIN, next, "Who is checking in?", buttons);
        }
        return proceed(c, m, channel, pending.isEmpty() ? ctx.candidates() : pending);
    }

    private Outcome pick(Conversation c, Message m) {
        List<Candidate> all = decode(c.context().get("cands"));
        String a = m.buttonAction();
        List<Candidate> chosen = ALL.equals(a) ? all : null;
        if (a != null && a.startsWith(PICK)) {
            int i = a.charAt(PICK.length()) - '0';
            chosen = i >= 0 && i < all.size() ? List.of(all.get(i)) : null;
        }
        if (chosen == null) { // not a tap on our buttons: ask again
            List<Button> buttons = new ArrayList<>();
            for (int i = 0; i < Math.min(2, all.size()); i++) {
                buttons.add(new Button(PICK + i, title(all.get(i).firstName())));
            }
            buttons.add(new Button(ALL, "All of us"));
            return new Outcome(State.CHECKIN, c.context(), "Please tap who is checking in.", buttons);
        }
        return proceed(c, m, c.context().get("ciChannel"), chosen);
    }

    private Outcome proceed(Conversation c, Message m, String channel, List<Candidate> chosen) {
        if (channel.endsWith(":geo")) {
            Map<String, String> next = new HashMap<>(c.context());
            next.put("step", "loc");
            next.put("cands", encode(chosen));
            next.put("ciChannel", channel);
            return new Outcome(State.CHECKIN, next, "Please share your location so we can confirm you are at the clinic. It is used once and not saved.");
        }
        return run(c, m, channel, chosen, null);
    }

    private Outcome location(Conversation c, Message m) {
        double[] loc = "location".equals(m.kind()) ? parse(m.text()) : null;
        if (loc == null) {
            return new Outcome(State.CHECKIN, c.context(), "Please share your location with the paperclip or plus button, then Location.");
        }
        return run(c, m, c.context().get("ciChannel"), decode(c.context().get("cands")), loc);
    }

    private Outcome run(Conversation c, Message m, String channel, List<Candidate> chosen, double[] loc) {
        String key = m.messageId() == null ? UUID.randomUUID().toString() : m.messageId();
        String wire = channel.replace(":geo", "");
        List<String> lines = new ArrayList<>();
        boolean multi = chosen.size() > 1;
        for (Candidate x : chosen) {
            CheckInResult r = core.checkIn(c.tenantId(), x.patientRef(), x.appointmentId(), wire, loc, "ci-" + key + "-" + x.appointmentId());
            if ("NEEDS_LOCATION".equals(r.status())) { // the clinic turned the geofence on after we asked: ask once
                Map<String, String> next = new HashMap<>(c.context());
                next.put("step", "loc");
                next.put("cands", encode(chosen));
                next.put("ciChannel", wire + ":geo");
                return new Outcome(State.CHECKIN, next, "Please share your location so we can confirm you are at the clinic. It is used once and not saved.");
            }
            String who = multi ? x.firstName() + ": " : "";
            lines.add(who + switch (r.status()) {
                case "CHECKED_IN" -> "you are checked in. Your token is " + r.token() + (r.position() == null ? "." : ", you are number " + r.position() + " in line.");
                case "ALREADY" -> "you are already checked in. Your token is " + r.token() + ".";
                case "OUTSIDE" -> "we could not confirm you are at the clinic. The front desk has been told and will check you in.";
                default -> "we could not find an appointment for you today. The front desk has been told.";
            });
        }
        return done(String.join("\n", lines));
    }

    private static Outcome done(String text) {
        return new Outcome(State.DONE, Map.of(), text);
    }

    private static String title(String name) {
        return name.length() <= TITLE_MAX ? name : name.substring(0, TITLE_MAX);
    }

    private static String clean(String s) {
        return s.replaceAll("[|;]", " ");
    }

    private static String encode(List<Candidate> cs) {
        return String.join(";", cs.stream().map(x -> x.patientRef() + "|" + x.appointmentId() + "|" + clean(x.firstName())).toList());
    }

    private static List<Candidate> decode(String s) {
        List<Candidate> out = new ArrayList<>();
        for (String part : s == null || s.isEmpty() ? new String[0] : s.split(";")) {
            String[] f = part.split("\\|", 3);
            out.add(new Candidate(f[0], f[2], f[1], false));
        }
        return out;
    }

    private static double[] parse(String text) {
        try {
            String[] p = text.trim().split(",");
            double lat = Double.parseDouble(p[0]), lng = Double.parseDouble(p[1]), acc = p.length > 2 ? Double.parseDouble(p[2]) : 0;
            return lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180 ? new double[]{lat, lng, acc} : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
