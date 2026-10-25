package com.nabd.hms.platform.plans;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanPricingTest {

    @Test
    void perDoctorMultipliesPerClinicIsFlat() {
        assertThat(PlanPricing.mrrCents("per_doctor", 50000, 4)).isEqualTo(200000);
        assertThat(PlanPricing.mrrCents("per_doctor", 50000, 0)).isZero();
        assertThat(PlanPricing.mrrCents("per_clinic", 50000, 4)).isEqualTo(50000);
    }

    @Test
    void unknownBasisAndOverflowAreRefused() {
        assertThatThrownBy(() -> PlanPricing.mrrCents("per_seat", 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlanPricing.mrrCents("per_doctor", Integer.MAX_VALUE, 2)).isInstanceOf(ArithmeticException.class);
    }
}
