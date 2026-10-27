package com.nabd.hms.queue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-024: a delay announced at 10:00 for a session ending 13:00 lapses at 13:00 (clock injected as a parameter). */
class Acc024DelayWindowTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);
    private static final List<LocalTime[]> TWO_SESSIONS = List.of(
            new LocalTime[] {LocalTime.of(9, 0), LocalTime.of(13, 0)}, new LocalTime[] {LocalTime.of(17, 0), LocalTime.of(20, 0)});

    private static Instant at(int h, int m) {
        return DAY.atTime(h, m).atZone(KOLKATA).toInstant();
    }

    @Test
    void lapsesAtTheEndOfTheSessionItWasAnnouncedIn() {
        assertThat(DelayWindow.expiresAt(TWO_SESSIONS, DAY, KOLKATA, at(10, 0))).isEqualTo(at(13, 0));
    }

    @Test
    void announcedBetweenSessionsItLapsesAtTheEndOfTheNextOne() {
        assertThat(DelayWindow.expiresAt(TWO_SESSIONS, DAY, KOLKATA, at(14, 30))).isEqualTo(at(20, 0));
    }

    @Test
    void afterTheLastSessionOrWithNoHoursItLapsesAtTheEndOfTheClinicDay() {
        Instant endOfDay = DAY.plusDays(1).atStartOfDay(KOLKATA).toInstant();
        assertThat(DelayWindow.expiresAt(TWO_SESSIONS, DAY, KOLKATA, at(21, 0))).isEqualTo(endOfDay);
        assertThat(DelayWindow.expiresAt(List.of(), DAY, KOLKATA, at(10, 0))).isEqualTo(endOfDay);
    }

    @Test
    void theBoundaryMinuteIsAlreadyInactive() {
        Instant expires = DelayWindow.expiresAt(TWO_SESSIONS, DAY, KOLKATA, at(10, 0));
        assertThat(expires.isAfter(at(13, 0))).isFalse(); // active(…) needs expiresAt strictly after now: at 13:00 it is over
    }
}
