package com.nabd.hms.messaging;

import com.nabd.hms.organization.api.QuietHours;
import com.nabd.hms.shared.notify.Priority;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** ADM-028: a recall due at 22:00 waits until 09:00; queue updates and OTPs never wait. */
class QuietHoursRuleTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final QuietHours.Window NIGHT = new QuietHours.Window(LocalTime.of(21, 0), LocalTime.of(9, 0));
    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);

    private static Instant at(int day, int h, int m) {
        return DAY.plusDays(day).atTime(h, m).atZone(KOLKATA).toInstant();
    }

    @Test
    void aRecallDueAtTenAtNightIsHeldUntilNineTheNextMorning() {
        assertThat(QuietHoursRule.releaseAt(NIGHT, KOLKATA, at(0, 22, 0), null, Priority.BULK)).isEqualTo(at(1, 9, 0));
    }

    @Test
    void afterMidnightItWaitsForTheSameMorning() {
        assertThat(QuietHoursRule.releaseAt(NIGHT, KOLKATA, at(1, 3, 30), null, Priority.REMINDER)).isEqualTo(at(1, 9, 0));
    }

    @Test
    void theEdgesOfTheWindow() {
        assertThat(QuietHoursRule.releaseAt(NIGHT, KOLKATA, at(0, 20, 59), null, Priority.BULK)).isNull();
        assertThat(QuietHoursRule.releaseAt(NIGHT, KOLKATA, at(0, 21, 0), null, Priority.BULK)).isEqualTo(at(1, 9, 0));
        assertThat(QuietHoursRule.releaseAt(NIGHT, KOLKATA, at(1, 8, 59), null, Priority.BULK)).isEqualTo(at(1, 9, 0));
        assertThat(QuietHoursRule.releaseAt(NIGHT, KOLKATA, at(1, 9, 0), null, Priority.BULK)).isNull();
    }

    @Test
    void queueUpdatesAndOtpsAreNeverHeld() {
        assertThat(QuietHoursRule.releaseAt(NIGHT, KOLKATA, at(0, 22, 0), null, Priority.TRANSACTIONAL)).isNull();
        assertThat(QuietHoursRule.releaseAt(NIGHT, KOLKATA, at(0, 22, 0), null, Priority.OTP)).isNull();
    }

    @Test
    void offMeansNoHold() {
        assertThat(QuietHoursRule.releaseAt(null, KOLKATA, at(0, 22, 0), null, Priority.BULK)).isNull();
    }

    @Test
    void aRequestedLaterTimeInsideTheWindowIsMovedToo() {
        assertThat(QuietHoursRule.releaseAt(NIGHT, KOLKATA, at(0, 12, 0), at(0, 23, 0), Priority.BULK)).isEqualTo(at(1, 9, 0));
        assertThat(QuietHoursRule.releaseAt(NIGHT, KOLKATA, at(0, 12, 0), at(0, 15, 0), Priority.BULK)).isEqualTo(at(0, 15, 0));
    }

    @Test
    void aSameDayWindowHoldsUntilItEnds() {
        var lunch = new QuietHours.Window(LocalTime.of(13, 0), LocalTime.of(15, 0));
        assertThat(QuietHoursRule.releaseAt(lunch, KOLKATA, at(0, 14, 0), null, Priority.BULK)).isEqualTo(at(0, 15, 0));
        assertThat(QuietHoursRule.releaseAt(lunch, KOLKATA, at(0, 16, 0), null, Priority.BULK)).isNull();
    }
}
