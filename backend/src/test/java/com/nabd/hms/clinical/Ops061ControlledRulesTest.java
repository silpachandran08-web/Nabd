package com.nabd.hms.clinical;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OPS-061 AC6, controlled-drug leg: every region selects ITS OWN safety dictionary (an AE or QA clinic no longer gets India's
 * Schedule H1 list). IN and SA have approved dictionaries and their controlled rule fires. AE and QA have none yet, so
 * prescribing fails closed (503) until a clinically approved list is imported: Needs clinical sign-off, no content is invented here.
 */
class Ops061ControlledRulesTest extends ApiTestBase {

    @Autowired SafetyDictionaryService dictionaries;

    @ParameterizedTest
    @ValueSource(strings = {"IN", "AE", "SA", "QA"})
    void aTenantRegionSelectsTheDictionaryOfThatRegion(String region) {
        assertThat(SafetyDictionary.regionOfTenant(region)).isEqualTo(region);
        assertThat(SafetyDictionary.regionOfTenant(null)).isEqualTo("IN");
    }

    @Test
    void inAndSaApplyTheirOwnControlledList() {
        SafetyDictionary in = dictionaries.forTenantRegion("IN");
        SafetyDictionary sa = dictionaries.forTenantRegion("SA");
        assertThat(in.region()).isEqualTo("IN");
        assertThat(sa.region()).isEqualTo("SA");
        assertThat(RxSafetyChecks.controlledSubstanceWarning(in, "TRAMADOL")).isNotNull();
        assertThat(RxSafetyChecks.controlledSubstanceWarning(sa, "DIAZEPAM")).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"AE", "QA"})
    void aRegionWithoutAnApprovedDictionaryFailsClosedInsteadOfUsingIndiasList(String region) {
        assertThatThrownBy(() -> dictionaries.forTenantRegion(region)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.getMessage()).contains(region);
        });
    }
}
