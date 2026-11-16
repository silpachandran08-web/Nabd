package com.nabd.comms.schedule;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

import static org.assertj.core.api.Assertions.assertThat;

/** S23 QA (t1) COM-024, DB-free: the hold boundaries of SendWindows in clinic local time and the URGENT classification. */
class S23QaCom024SendWindowsTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");

    static Instant at(ZoneId z, LocalDate d, int h, int m) {
        return LocalDateTime.of(d, LocalTime.of(h, m)).atZone(z).toInstant();
    }

    static SendWindows.Settings india() {
        return new SendWindows.Settings(IST, LocalTime.of(21, 0), LocalTime.of(9, 0), null, 20, LocalTime.of(11, 30), LocalTime.of(13, 30), 0, 0);
    }

    static SendWindows.Settings riyadh() {
        return new SendWindows.Settings(RIYADH, null, null, PrayerTimes.Method.UMM_AL_QURA, 20, LocalTime.of(11, 30), LocalTime.of(13, 30), 24.7136, 46.6753);
    }

    // C24-U01: quiet 21:00-09:00 boundaries
    @Test
    void c24u01_indiaQuietHoursBoundaries() {
        LocalDate d = LocalDate.of(2026, 11, 18);
        assertThat(SendWindows.nextAllowed(india(), at(IST, d, 22, 10))).isEqualTo(at(IST, d.plusDays(1), 9, 0));
        assertThat(SendWindows.nextAllowed(india(), at(IST, d, 8, 59))).isEqualTo(at(IST, d, 9, 0));
        assertThat(SendWindows.nextAllowed(india(), at(IST, d, 9, 0))).isEqualTo(at(IST, d, 9, 0));
        assertThat(SendWindows.nextAllowed(india(), at(IST, d, 20, 59))).isEqualTo(at(IST, d, 20, 59));
        assertThat(SendWindows.nextAllowed(india(), at(IST, d, 21, 0))).isEqualTo(at(IST, d.plusDays(1), 9, 0));
        assertThat(SendWindows.nextAllowed(india(), at(IST, d, 0, 0))).isEqualTo(at(IST, d, 9, 0));
    }

    // C24-U02: Friday 11:30-13:30 in Riyadh: every minute lands at or after 13:30 and outside every hold
    @Test
    void c24u02_fridayBlockInRiyadh() {
        LocalDate fri = LocalDate.of(2026, 11, 16).with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
        for (int[] hm : new int[][]{{11, 30}, {12, 0}, {13, 29}}) {
            Instant next = SendWindows.nextAllowed(riyadh(), at(RIYADH, fri, hm[0], hm[1]));
            assertThat(next).as("%s:%s", hm[0], hm[1]).isAfterOrEqualTo(at(RIYADH, fri, 13, 30));
            assertThat(SendWindows.holdEnd(riyadh(), next)).as("allowed minute is outside every hold").isNull();
        }
        // a Thursday 12:00 is not held by the Friday block
        LocalDate thu = fri.minusDays(1);
        Instant thuNoon = at(RIYADH, thu, 12, 0);
        Instant thuNext = SendWindows.nextAllowed(riyadh(), thuNoon);
        assertThat(Duration.between(thuNoon, thuNext)).as("Thursday noon is held at most by a prayer buffer").isLessThanOrEqualTo(Duration.ofMinutes(21));
    }

    // C24-U03: one minute after an adhan -> adhan + buffer, rounded up to the minute
    @Test
    void c24u03_insideAPrayerBufferDefersToItsEnd() {
        LocalDate d = LocalDate.of(2026, 11, 18); // a Wednesday
        for (Instant adhan : PrayerTimes.adhans(d, 24.7136, 46.6753, PrayerTimes.Method.UMM_AL_QURA)) {
            Instant next = SendWindows.nextAllowed(riyadh(), adhan.plusSeconds(60));
            Instant end = adhan.plus(Duration.ofMinutes(20));
            assertThat(next).isAfterOrEqualTo(end).isBefore(end.plusSeconds(61));
            assertThat(SendWindows.nextAllowed(riyadh(), adhan.minusSeconds(60))).as("just before the adhan is free").isEqualTo(adhan.minusSeconds(60));
        }
    }

    // C24-U04: URGENT classification
    @Test
    void c24u04_urgentClasses() {
        assertThat(SendWindows.urgent("otp", null)).isTrue();
        assertThat(SendWindows.urgent("REMINDER", "DOCTOR_DELAY")).isTrue();
        assertThat(SendWindows.urgent("TRANSACTIONAL", "secure_link")).isTrue();
        assertThat(SendWindows.urgent("TRANSACTIONAL", "delay_notice")).isTrue();
        assertThat(SendWindows.urgent("BULK", "review_request")).isFalse();
        assertThat(SendWindows.urgent("BULK", "recall_due")).isFalse();
        assertThat(SendWindows.urgent(null, null)).isFalse();
    }
}
