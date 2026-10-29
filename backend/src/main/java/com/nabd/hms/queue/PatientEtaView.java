package com.nabd.hms.queue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * ACC-018: the ONE shaper that turns an ETA projection into what a patient may see (WhatsApp status replies, leave-home
 * alerts, the lobby display, patient web, the app). Fields a mode does not allow are null, so no surface can leak them.
 * Pure: no I/O, the clinic zone is a parameter. English text only; translation of the fixed phrases belongs to each surface.
 */
public final class PatientEtaView {

    public static final List<String> MODES = List.of("exact", "range", "position", "delay_only", "hidden");
    public static final String DEFAULT_MODE = "range";

    /** What the ETA engine knows; delayMinutes is the doctor's active delay, null or 0 when none. */
    public record Input(Instant p50, Instant p80, Integer position, Integer delayMinutes) {
    }

    /** exactAt: clock time (exact). from/to: window (range). position: 1-based place in line. delayMinutes: active delay notice. */
    public record View(String mode, Instant exactAt, Instant from, Instant to, Integer position, Integer delayMinutes,
                       boolean leaveHomeAlert, String text) {
    }

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private PatientEtaView() {
    }

    public static boolean isMode(String mode) {
        return mode != null && MODES.contains(mode);
    }

    public static View shape(String mode, Input in, ZoneId zone) {
        String m = isMode(mode) ? mode : DEFAULT_MODE;
        Integer delay = in.delayMinutes() != null && in.delayMinutes() > 0 ? in.delayMinutes() : null;
        String delayText = delay == null ? null : "The doctor is running about " + delay + " min late.";
        return switch (m) {
            case "exact" -> in.p50() == null ? new View(m, null, null, null, null, delay, false, delayText)
                    : new View(m, in.p50(), null, null, null, delay, true,
                            join("Your consult starts at " + HH_MM.format(in.p50().atZone(zone)), delayText));
            case "range" -> in.p50() == null ? new View(m, null, null, null, null, delay, false, delayText)
                    : new View(m, null, in.p50(), in.p80() == null || in.p80().isBefore(in.p50()) ? in.p50() : in.p80(), null, delay, true,
                            join("Expected " + HH_MM.format(in.p50().atZone(zone)) + "-"
                                    + HH_MM.format((in.p80() == null || in.p80().isBefore(in.p50()) ? in.p50() : in.p80()).atZone(zone)), delayText));
            case "position" -> in.position() == null ? new View(m, null, null, null, null, null, true, null)
                    : new View(m, null, null, null, in.position(), null, true, "You are " + ordinal(in.position()) + " in line");
            case "delay_only" -> new View(m, null, null, null, null, delay, false, delayText);
            default -> new View("hidden", null, null, null, null, null, false, null); // hidden: no ETA anywhere, no leave-home alert
        };
    }

    private static String join(String a, String b) {
        return b == null ? a : a + " " + b;
    }

    static String ordinal(int n) {
        int mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) {
            return n + "th";
        }
        return n + switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }
}
