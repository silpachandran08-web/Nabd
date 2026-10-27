package com.nabd.hms.messaging;

import com.nabd.hms.organization.api.QuietHours;
import com.nabd.hms.shared.notify.Priority;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** ADM-028: reminders and bulk messages that fall in the clinic's quiet hours wait for the end of the window. OTPs and transactional messages (queue and delay updates) never wait. */
final class QuietHoursRule {

    private QuietHoursRule() {
    }

    /** @return the earliest send time: {@code requested} (may be null = now) unless it falls in the quiet window, then the next end of the window */
    static Instant releaseAt(QuietHours.Window w, ZoneId zone, Instant now, Instant requested, Priority priority) {
        if (w == null || priority == Priority.OTP || priority == Priority.TRANSACTIONAL) {
            return requested;
        }
        Instant at = requested != null && requested.isAfter(now) ? requested : now;
        ZonedDateTime z = at.atZone(zone);
        LocalTime t = z.toLocalTime();
        boolean wraps = w.from().isAfter(w.to());
        boolean quiet = wraps ? !t.isBefore(w.from()) || t.isBefore(w.to()) : !t.isBefore(w.from()) && t.isBefore(w.to());
        if (!quiet) {
            return requested;
        }
        ZonedDateTime release = z.with(w.to());
        return (release.isAfter(z) ? release : release.plusDays(1)).toInstant();
    }
}
