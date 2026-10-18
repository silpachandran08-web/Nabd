package com.nabd.hms.clinical;

import com.nabd.hms.support.Hazard;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** CLN-003 / hazard H-07: age and weight-age guards, boundaries pinned. */
@Hazard("H-07")
class DoseWeightPolicyTest {

    private static final ZoneId Z = ZoneId.of("UTC");
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    @Test
    void adultBoundaryIs18Years() {
        assertThat(DoseWeightPolicy.isAdult(TODAY.minusYears(18).plusDays(1), TODAY)).isFalse(); // 17y364d
        assertThat(DoseWeightPolicy.isAdult(TODAY.minusYears(18), TODAY)).isTrue();             // 18y0d
    }

    private static Instant daysAgo(int d) {
        return TODAY.minusDays(d).atStartOfDay(Z).toInstant();
    }

    @Test
    void under2YearsWindowIs30Days() {
        LocalDate dob = TODAY.minusMonths(14);
        assertThat(DoseWeightPolicy.isStale(daysAgo(30), dob, TODAY, Z)).isFalse();
        assertThat(DoseWeightPolicy.isStale(daysAgo(31), dob, TODAY, Z)).isTrue();
        assertThat(DoseWeightPolicy.isStale(daysAgo(40), dob, TODAY, Z)).isTrue();
    }

    @Test
    void from2YearsWindowIs90Days() {
        LocalDate dob = TODAY.minusYears(2); // exactly 2y0d is the older band
        assertThat(DoseWeightPolicy.isStale(daysAgo(40), dob, TODAY, Z)).isFalse();
        assertThat(DoseWeightPolicy.isStale(daysAgo(90), dob, TODAY, Z)).isFalse();
        assertThat(DoseWeightPolicy.isStale(daysAgo(91), dob, TODAY, Z)).isTrue();
        assertThat(DoseWeightPolicy.isStale(daysAgo(40), TODAY.minusYears(2).plusDays(1), TODAY, Z)).isTrue(); // 1y364d
    }

    @Test
    void unknownRecordingTimeIsStaleAndTodayIsFresh() {
        assertThat(DoseWeightPolicy.isStale(null, TODAY.minusYears(5), TODAY, Z)).isTrue();
        assertThat(DoseWeightPolicy.isStale(daysAgo(0), TODAY.minusMonths(3), TODAY, Z)).isFalse();
    }
}
