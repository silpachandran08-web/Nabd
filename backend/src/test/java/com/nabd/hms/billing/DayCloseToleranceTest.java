package com.nabd.hms.billing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** FIN-005 unit: cash variance tolerance per currency, and the clinic policy (minor units) winning over the default. */
class DayCloseToleranceTest {

    @Test
    void defaultsWithoutAPolicy() {
        assertThat(DayCloseService.tolerance("AED", Optional.empty())).isEqualByComparingTo("20.00");
        assertThat(DayCloseService.tolerance("QAR", Optional.empty())).isEqualByComparingTo("20.00");
        assertThat(DayCloseService.tolerance("SAR", Optional.empty())).isEqualByComparingTo("20.00");
        assertThat(DayCloseService.tolerance("INR", Optional.empty())).isEqualByComparingTo("200.00");
    }

    @Test
    void policyInMinorUnitsWins() {
        assertThat(DayCloseService.tolerance("AED", Optional.of("5000"))).isEqualByComparingTo("50.00");
        assertThat(DayCloseService.tolerance("INR", Optional.of("0"))).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(DayCloseService.tolerance("AED", Optional.of("1")).scale()).isEqualTo(2);
    }
}
