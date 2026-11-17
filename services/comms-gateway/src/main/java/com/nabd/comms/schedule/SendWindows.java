package com.nabd.comms.schedule;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;

/**
 * COM-024 (FRD SYS-03, CMX-21): when a non-urgent send may go out, in the clinic's own clock. Holds: the clinic's quiet hours,
 * and where prayer windows are on, each adhan plus the buffer and the Friday block. URGENT sends bypass every hold.
 */
public final class SendWindows {

    /** URGENT purposes (template name = Core purpose code): OTP, doctor delay / delay notice, and the secure link the patient asked for. */
    // shortcut: queue "your turn" and "leave now" have no purpose yet; add their codes here when Core gets them
    static final Set<String> URGENT_PURPOSES = Set.of("otp", "doctor_delay", "delay_notice", "secure_link");

    /**
     * @param quietFrom null = quiet hours off
     * @param method    null = prayer windows off (then the Friday block is off too)
     */
    public record Settings(ZoneId zone, LocalTime quietFrom, LocalTime quietTo, PrayerTimes.Method method, int bufferMinutes,
                           LocalTime fridayFrom, LocalTime fridayTo, double latitude, double longitude) {
    }

    private SendWindows() {
    }

    public static boolean urgent(String priority, String templateName) {
        return "OTP".equalsIgnoreCase(priority) || (templateName != null && URGENT_PURPOSES.contains(templateName.toLowerCase(Locale.ROOT)));
    }

    /** The first minute at or after {@code at} that is outside every hold ({@code at} itself when nothing holds it). */
    public static Instant nextAllowed(Settings s, Instant at) {
        Instant t = at;
        for (int i = 0; i < 24; i++) { // holds can chain (quiet end lands in Fajr, Friday block ends in Dhuhr's buffer)
            Instant end = holdEnd(s, t);
            if (end == null) {
                return t;
            }
            t = end;
        }
        return t;
    }

    /** The latest end of the holds {@code t} is inside, or null. */
    static Instant holdEnd(Settings s, Instant t) {
        ZonedDateTime z = t.atZone(s.zone());
        Instant end = null;
        if (s.quietFrom() != null && inside(z.toLocalTime(), s.quietFrom(), s.quietTo())) {
            ZonedDateTime e = z.with(s.quietTo()).truncatedTo(ChronoUnit.MINUTES);
            end = later(end, (e.isAfter(z) ? e : e.plusDays(1)).toInstant());
        }
        if (s.method() != null) {
            if (s.bufferMinutes() > 0) {
                for (Instant adhan : PrayerTimes.adhans(z.toLocalDate(), s.latitude(), s.longitude(), s.method())) {
                    Instant windowEnd = adhan.plus(Duration.ofMinutes(s.bufferMinutes()));
                    if (!t.isBefore(adhan) && t.isBefore(windowEnd)) {
                        end = later(end, ceilMinute(windowEnd));
                    }
                }
            }
            if (z.getDayOfWeek() == DayOfWeek.FRIDAY && s.fridayFrom() != null && inside(z.toLocalTime(), s.fridayFrom(), s.fridayTo())) {
                end = later(end, z.with(s.fridayTo()).toInstant());
            }
        }
        return end;
    }

    /** [from, to) in local time; from after to wraps midnight (21:00-09:00). */
    private static boolean inside(LocalTime t, LocalTime from, LocalTime to) {
        return from.isAfter(to) ? !t.isBefore(from) || t.isBefore(to) : !t.isBefore(from) && t.isBefore(to);
    }

    private static Instant ceilMinute(Instant i) {
        Instant m = i.truncatedTo(ChronoUnit.MINUTES);
        return m.equals(i) ? m : m.plus(Duration.ofMinutes(1));
    }

    private static Instant later(Instant a, Instant b) {
        return a == null || b.isAfter(a) ? b : a;
    }
}
