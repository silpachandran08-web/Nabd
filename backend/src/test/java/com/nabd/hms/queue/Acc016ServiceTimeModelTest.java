package com.nabd.hms.queue;

import com.nabd.hms.queue.ServiceTimeModel.Dist;
import com.nabd.hms.queue.ServiceTimeModel.Ewma;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** ACC-016: the pure model with fixed numbers (no Spring, no database). */
class Acc016ServiceTimeModelTest {

    @Test
    void fiveObservationsBlendFiveTwentyFifthsDoctorAndTwentyTwentyFifthsClinic() {
        // own 10 min, clinic 20 min: 5/25 * 10 + 20/25 * 20 = 18 min
        assertThat(ServiceTimeModel.blend(5, 600, 1200)).isCloseTo(1080, within(1e-9));
        Dist blended = ServiceTimeModel.blend(5, new Dist(600, 0.3), new Dist(1200, 0.5));
        assertThat(blended.medianS()).isCloseTo(1080, within(1e-9));
        assertThat(blended.sigma()).isCloseTo(0.46, within(1e-9));
        assertThat(ServiceTimeModel.blend(0, 600, 1200)).isEqualTo(1200); // nothing learned: the prior
        assertThat(ServiceTimeModel.blend(20, 600, 1200)).isCloseTo(900, within(1e-9)); // half and half at 20
    }

    @Test
    void ewmaMovesTenPercentOfTheWayAndShrinksTheVariance() {
        Ewma e = ServiceTimeModel.ewma(Math.log(600), 0.04, Math.log(1200));
        assertThat(e.mu()).isCloseTo(Math.log(600) + 0.1 * Math.log(2), within(1e-12));
        double d = Math.log(2);
        assertThat(e.var()).isCloseTo(0.9 * (0.04 + 0.1 * d * d), within(1e-12));
    }

    @Test
    void aConsultOverFourTimesTheMedianIsCappedAndMovesTheEwmaNoMoreThanTheCap() {
        assertThat(ServiceTimeModel.isOverCap(2401, 600)).isTrue();
        assertThat(ServiceTimeModel.isOverCap(2400, 600)).isFalse(); // exactly 4x is not over
        double capped = ServiceTimeModel.cap(36000, 600);
        assertThat(capped).isEqualTo(2400);
        Ewma viaCap = ServiceTimeModel.ewma(Math.log(600), 0.04, Math.log(capped));
        Ewma atCap = ServiceTimeModel.ewma(Math.log(600), 0.04, Math.log(2400));
        Ewma uncapped = ServiceTimeModel.ewma(Math.log(600), 0.04, Math.log(36000));
        assertThat(viaCap).isEqualTo(atCap);
        assertThat(uncapped.mu()).isGreaterThan(viaCap.mu());
    }

    @Test
    void remainingTimeOfARunningConsultIsConditionalAndNeverUnderAMinute() {
        Dist d = new Dist(600, 0.4);
        assertThat(ServiceTimeModel.remaining(d, 0)).isCloseTo(d.meanS(), within(1e-6));
        double fresh = ServiceTimeModel.remaining(d, 60);
        double late = ServiceTimeModel.remaining(d, 1500);
        assertThat(fresh).isGreaterThan(300).isLessThan(d.meanS());
        assertThat(late).isGreaterThanOrEqualTo(60);
        assertThat(ServiceTimeModel.remaining(d, 100000)).isEqualTo(60); // far in the tail
    }

    @Test
    void quantilesOfTheLogNormal() {
        Dist d = new Dist(600, 0.4);
        assertThat(d.p80S()).isCloseTo(600 * Math.exp(0.4 * 0.8416212335729143), within(1e-6));
        assertThat(Dist.fromMedianP80(600, d.p80S()).sigma()).isCloseTo(0.4, within(1e-9));
        assertThat(ServiceTimeModel.phi(0)).isCloseTo(0.5, within(1e-7));
        assertThat(ServiceTimeModel.phi(1.2815515655)).isCloseTo(0.9, within(1e-6));
    }
}
