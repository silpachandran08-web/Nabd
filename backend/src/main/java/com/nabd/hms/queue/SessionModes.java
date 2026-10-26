package com.nabd.hms.queue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static com.nabd.hms.queue.QueueModels.WorkingHoursRow;

/** ACC-011: pure helpers for session modes (slot | token | hybrid). */
final class SessionModes {
    static final String SLOT = "slot", TOKEN = "token", HYBRID = "hybrid";
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private SessionModes() {
    }

    /** A mode change applies to future dates only: dates before effective_from keep the previous mode. */
    static String modeOn(WorkingHoursRow wh, LocalDate date) {
        return wh.effectiveFrom() != null && date.isBefore(wh.effectiveFrom()) ? wh.previousSessionMode() : wh.sessionMode();
    }

    /**
     * Honest range for a reserved place: token n is expected after n-1 average consults, shown as a window two consults
     * wide, rounded to 5 minutes and kept inside the session. Never a single time.
     */
    static String expectedRange(Instant sessionStart, Instant sessionEnd, int token, double avgConsultMinutes, ZoneId zone) {
        long widthMin = Math.max(10, Math.round(avgConsultMinutes * 2 / 5.0) * 5);
        long total = Duration.between(sessionStart, sessionEnd).toMinutes();
        long lo = Math.min(Math.round((token - 1) * avgConsultMinutes / 5.0) * 5, Math.max(0, total - widthMin));
        long hi = Math.min(lo + widthMin, total);
        return sessionStart.plus(Duration.ofMinutes(lo)).atZone(zone).format(HHMM) + "-"
                + sessionStart.plus(Duration.ofMinutes(hi)).atZone(zone).format(HHMM);
    }
}
