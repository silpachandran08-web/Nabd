package com.nabd.hms.clinical;

import com.nabd.hms.support.Hazard;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** DOC-021. NOT EXECUTED in the authoring environment. */
@Hazard("H-09")
class RxSafetyChecksPregnancyStatusTest {

    @Test
    void recordedPregnantOrLactatingEscalatesForARiskDrug() {
        assertThat(RxSafetyChecks.recordedStatusWarning(SafetyDictionaryFixtures.IN, "Warfarin 5mg", "pregnant")).contains("PREGNANT");
        assertThat(RxSafetyChecks.recordedStatusWarning(SafetyDictionaryFixtures.IN, "Warfarin 5mg", "lactating")).contains("LACTATING");
    }

    @Test
    void otherStatusesNeverProduceTheEscalationSoBehaviourIsUnchanged() {
        for (String status : new String[]{null, "not_pregnant", "unknown"}) {
            assertThat(RxSafetyChecks.recordedStatusWarning(SafetyDictionaryFixtures.IN, "Warfarin 5mg", status)).isNull();
        }
        assertThat(RxSafetyChecks.recordedStatusWarning(SafetyDictionaryFixtures.IN, "Paracetamol", "pregnant")).isNull();
    }

    @Test
    void theExistingRuleIsUntouchedByARecordedNotPregnantStatus() {
        LocalDate today = LocalDate.of(2026, 1, 1);
        assertThat(RxSafetyChecks.pregnancyWarning(SafetyDictionaryFixtures.IN, "Warfarin", "female", LocalDate.of(1995, 1, 1), today)).isNotNull();
    }
}
