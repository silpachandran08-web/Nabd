package com.nabd.hms.queue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-018: the one shaper, per mode. No Spring, no database. */
class Acc018PatientEtaViewTest {

    static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    static final Instant P50 = Instant.parse("2026-10-26T05:12:00Z"); // 10:42 in Kolkata
    static final Instant P80 = Instant.parse("2026-10-26T05:32:00Z"); // 11:02

    static PatientEtaView.View shape(String mode, Integer position, Integer delay) {
        return PatientEtaView.shape(mode, new PatientEtaView.Input(P50, P80, position, delay), KOLKATA);
    }

    @Test
    void exactShowsOneClockTimeAndLeaveHomeIsAllowed() {
        var v = shape("exact", 3, null);
        assertThat(v.text()).isEqualTo("Your consult starts at 10:42");
        assertThat(v.exactAt()).isEqualTo(P50);
        assertThat(v.from()).isNull();
        assertThat(v.position()).isNull();
        assertThat(v.leaveHomeAlert()).isTrue();
    }

    @Test
    void rangeIsTheDefaultAndShowsAWindow() {
        var v = shape("range", 3, null);
        assertThat(v.text()).isEqualTo("Expected 10:42-11:02");
        assertThat(v.exactAt()).isNull();
        assertThat(v.from()).isEqualTo(P50);
        assertThat(v.to()).isEqualTo(P80);
        assertThat(shape(null, 3, null).mode()).isEqualTo("range"); // unknown or missing mode = range
        assertThat(shape("bogus", 3, null).mode()).isEqualTo("range");
    }

    @Test
    void positionShowsOnlyThePlaceInLine() {
        var v = shape("position", 3, 20);
        assertThat(v.text()).isEqualTo("You are 3rd in line");
        assertThat(v.exactAt()).isNull();
        assertThat(v.from()).isNull();
        assertThat(v.delayMinutes()).isNull();
        assertThat(PatientEtaView.ordinal(1)).isEqualTo("1st");
        assertThat(PatientEtaView.ordinal(2)).isEqualTo("2nd");
        assertThat(PatientEtaView.ordinal(11)).isEqualTo("11th");
        assertThat(PatientEtaView.ordinal(12)).isEqualTo("12th");
        assertThat(PatientEtaView.ordinal(13)).isEqualTo("13th");
        assertThat(PatientEtaView.ordinal(21)).isEqualTo("21st");
        assertThat(PatientEtaView.ordinal(102)).isEqualTo("102nd");
    }

    @Test
    void delayOnlyHasNoClockTimeNoPositionAndSaysNothingWithoutADelay() {
        var quiet = shape("delay_only", 3, null);
        assertThat(quiet.text()).isNull();
        assertThat(quiet.exactAt()).isNull();
        assertThat(quiet.from()).isNull();
        assertThat(quiet.position()).isNull();
        var late = shape("delay_only", 3, 20);
        assertThat(late.text()).isEqualTo("The doctor is running about 20 min late.");
        assertThat(late.text()).doesNotContainPattern("\\d\\d:\\d\\d");
        assertThat(late.position()).isNull();
        assertThat(late.leaveHomeAlert()).isFalse();
        assertThat(shape("delay_only", 3, 0).text()).isNull(); // 0 is no delay
    }

    @Test
    void hiddenShowsNothingAndSchedulesNoLeaveHomeAlert() {
        var v = shape("hidden", 3, 20);
        assertThat(v.text()).isNull();
        assertThat(v.exactAt()).isNull();
        assertThat(v.from()).isNull();
        assertThat(v.position()).isNull();
        assertThat(v.delayMinutes()).isNull();
        assertThat(v.leaveHomeAlert()).isFalse();
    }

    @Test
    void withoutAPredictionExactAndRangeShowOnlyTheDelayNotice() {
        var v = PatientEtaView.shape("range", new PatientEtaView.Input(null, null, 2, 10), KOLKATA);
        assertThat(v.from()).isNull();
        assertThat(v.leaveHomeAlert()).isFalse();
        assertThat(v.text()).isEqualTo("The doctor is running about 10 min late.");
    }

    @Test
    void anEveryModeNeverLeaksAFieldItDoesNotOwn() {
        for (String mode : PatientEtaView.MODES) {
            var v = shape(mode, 4, 15);
            assertThat(v.exactAt() != null).as(mode + " exactAt").isEqualTo(mode.equals("exact"));
            assertThat(v.from() != null).as(mode + " range").isEqualTo(mode.equals("range"));
            assertThat(v.position() != null).as(mode + " position").isEqualTo(mode.equals("position"));
        }
    }
}
