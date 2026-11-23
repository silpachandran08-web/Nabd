package com.nabd.hms.queue;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/** ADM-042: how many minutes late a doctor is on the owner's live board (no Spring context). */
class Adm042LateMinutesTest {

    private static final LocalTime NINE = LocalTime.of(9, 0), ONE = LocalTime.of(13, 0);

    @Test
    void notStartedTwentyFiveMinutesAfterTheSessionStartIsTwentyFiveMinutesLate() {
        assertThat(LiveBoardQueries.lateMinutes(0, false, NINE, ONE, LocalTime.of(9, 25))).isEqualTo(25);
    }

    @Test
    void aStartedDoctorIsOnlyAsLateAsTheAnnouncedDelay() {
        assertThat(LiveBoardQueries.lateMinutes(0, true, NINE, ONE, LocalTime.of(9, 25))).isZero();
        assertThat(LiveBoardQueries.lateMinutes(30, true, NINE, ONE, LocalTime.of(9, 25))).isEqualTo(30);
    }

    @Test
    void theLargerOfTheAnnouncedDelayAndTheOverdueStartCounts() {
        assertThat(LiveBoardQueries.lateMinutes(15, false, NINE, ONE, LocalTime.of(9, 40))).isEqualTo(40);
        assertThat(LiveBoardQueries.lateMinutes(45, false, NINE, ONE, LocalTime.of(9, 40))).isEqualTo(45);
    }

    @Test
    void beforeTheStartAfterTheLastSessionWithoutHoursOrOnAnotherCalendarDayNobodyIsLate() {
        assertThat(LiveBoardQueries.lateMinutes(0, false, NINE, ONE, LocalTime.of(8, 59))).isZero();
        assertThat(LiveBoardQueries.lateMinutes(0, false, NINE, ONE, ONE)).isZero();
        assertThat(LiveBoardQueries.lateMinutes(0, false, null, null, LocalTime.of(9, 25))).isZero(); // no hours today, or on whole-day leave
        assertThat(LiveBoardQueries.lateMinutes(0, false, NINE, ONE, null)).isZero(); // past midnight of a late clinic day
    }
}
