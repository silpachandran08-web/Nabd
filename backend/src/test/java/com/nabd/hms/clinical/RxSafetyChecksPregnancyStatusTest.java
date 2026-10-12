package com.nabd.hms.clinical;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** DOC-021. NOT EXECUTED in the authoring environment. */
class RxSafetyChecksPregnancyStatusTest {

    @Test
    void recordedPregnantOrLactatingEscalatesForARiskDrug() {
        assertThat(RxSafetyChecks.recordedStatusWarning("Warfarin 5mg", "pregnant")).contains("PREGNANT");
        assertThat(RxSafetyChecks.recordedStatusWarning("Warfarin 5mg", "lactating")).contains("LACTATING");
    }

    @Test
    void otherStatusesNeverProduceTheEscalationSoBehaviourIsUnchanged() {
        for (String status : new String[]{null, "not_pregnant", "unknown"}) {
            assertThat(RxSafetyChecks.recordedStatusWarning("Warfarin 5mg", status)).isNull();
        }
        assertThat(RxSafetyChecks.recordedStatusWarning("Paracetamol", "pregnant")).isNull();
    }

    @Test
    void theExistingRuleIsUntouchedByARecordedNotPregnantStatus() {
        LocalDate today = LocalDate.of(2026, 1, 1);
        assertThat(RxSafetyChecks.pregnancyWarning("Warfarin", "female", LocalDate.of(1995, 1, 1), today)).isNotNull();
    }
}
