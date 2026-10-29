package com.nabd.comms.quality;

import com.nabd.comms.quality.QualityPolicy.Mode;
import com.nabd.comms.send.Lane;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static com.nabd.comms.quality.QualityPolicy.decide;
import static org.assertj.core.api.Assertions.assertThat;

/** COM-021 unit: the throttle policy and the block-rate computation. */
class QualityPolicyTest {

    static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

    static QualityPolicy.State st(String rating, double rate, Mode mode, Instant green) {
        return new QualityPolicy.State(rating, rate, mode, false, green);
    }

    @Test
    void yellowPausesBulkButRemindersContinue() {
        var d = decide(st("YELLOW", 0, Mode.auto, null), NOW);
        assertThat(d.mode()).isEqualTo(Mode.bulk_paused);
        assertThat(d.mode().lanes()).contains(Lane.REMINDER, Lane.OTP, Lane.TRANSACTIONAL).doesNotContain(Lane.BULK);
    }

    @Test
    void redAllowsOnlyRepliesOtpsAndQueueUpdates() {
        var d = decide(st("RED", 0, Mode.auto, null), NOW);
        assertThat(d.mode()).isEqualTo(Mode.replies_only);
        assertThat(d.mode().lanes()).containsExactlyInAnyOrder(Lane.OTP, Lane.TRANSACTIONAL);
    }

    @Test
    void blockRateAboveOnePointFivePercentPausesBulkEvenWhenGreen() {
        assertThat(decide(st("GREEN", 0.0151, Mode.auto, NOW.minusSeconds(10)), NOW)).extracting(QualityPolicy.Decision::mode, QualityPolicy.Decision::reason)
                .containsExactly(Mode.bulk_paused, "block_rate");
        assertThat(decide(st("GREEN", 0.015, Mode.auto, null), NOW).mode()).as("exactly 1.5 % is allowed").isEqualTo(Mode.auto);
    }

    @Test
    void greenFor48HoursResumesAutomaticallyButNotBefore() {
        Instant since = NOW.minus(Duration.ofHours(48));
        assertThat(decide(st("GREEN", 0, Mode.bulk_paused, since), NOW)).extracting(QualityPolicy.Decision::mode, QualityPolicy.Decision::reason)
                .containsExactly(Mode.auto, "recovered");
        assertThat(decide(st("GREEN", 0, Mode.bulk_paused, since.plusSeconds(1)), NOW).mode()).isEqualTo(Mode.bulk_paused);
        assertThat(decide(st("GREEN", 0, Mode.bulk_paused, null), NOW).greenSince()).as("the GREEN run starts now").isEqualTo(NOW);
        assertThat(decide(st("YELLOW", 0, Mode.bulk_paused, since), NOW).greenSince()).as("a dip breaks the run").isNull();
    }

    @Test
    void anActiveOverrideWinsOverTheAutomaticState() {
        var s = new QualityPolicy.State("RED", 0, Mode.auto, true, null);
        assertThat(decide(s, NOW).mode()).isEqualTo(Mode.auto);
    }

    @Test
    void blockRateIgnoresTinySamples() {
        assertThat(QualityPolicy.blockRate(5, 49)).isZero();
        assertThat(QualityPolicy.blockRate(2, 100)).isEqualTo(0.02);
    }
}
