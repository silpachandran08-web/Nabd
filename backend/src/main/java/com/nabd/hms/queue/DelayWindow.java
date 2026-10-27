package com.nabd.hms.queue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/** ACC-024: when a delay announced at {@code now} lapses: the end of the session it was announced in (or the next one), else the end of the clinic day. */
final class DelayWindow {

    private DelayWindow() {
    }

    /** @param sessions (start, end) pairs of the doctor's sessions that weekday, earliest first */
    static Instant expiresAt(List<LocalTime[]> sessions, LocalDate day, ZoneId zone, Instant now) {
        for (LocalTime[] s : sessions) {
            Instant end = day.atTime(s[1]).atZone(zone).toInstant();
            if (end.isAfter(now)) {
                return end;
            }
        }
        return day.plusDays(1).atStartOfDay(zone).toInstant();
    }
}
