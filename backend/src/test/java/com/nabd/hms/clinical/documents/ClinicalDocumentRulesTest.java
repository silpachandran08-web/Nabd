package com.nabd.hms.clinical.documents;

import com.nabd.hms.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClinicalDocumentRulesTest {

    @Test
    void sanitiseTrimsAndDropsBlankValues() {
        Map<String, String> out = ClinicalDocumentRules.sanitise("referral",
                Map.of("referredTo", "  Cardiology ", "reason", "   "));
        assertThat(out).containsOnlyKeys("referredTo").containsEntry("referredTo", "Cardiology");
    }

    @Test
    void sanitiseRejectsFieldsOfAnotherType() {
        assertThatThrownBy(() -> ClinicalDocumentRules.sanitise("referral", Map.of("restFrom", "2026-01-01")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void sanitiseRejectsUnknownTypeAndBadDate() {
        assertThatThrownBy(() -> ClinicalDocumentRules.sanitise("prescription", Map.of()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ClinicalDocumentRules.sanitise("certificate", Map.of("restFrom", "01/02/2026")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void sanitiseRejectsOverlongValue() {
        assertThatThrownBy(() -> ClinicalDocumentRules.sanitise("certificate",
                Map.of("remarks", "x".repeat(ClinicalDocumentRules.MAX_FIELD_LENGTH + 1))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void issuingNeedsTheRequiredFields() {
        assertThatThrownBy(() -> ClinicalDocumentRules.requireIssuable("referral", Map.of("referredTo", "ENT")))
                .isInstanceOf(ApiException.class);
        ClinicalDocumentRules.requireIssuable("referral", Map.of("referredTo", "ENT", "reason", "r"));
        assertThatThrownBy(() -> ClinicalDocumentRules.requireIssuable("visit_summary", Map.of()))
                .isInstanceOf(ApiException.class);
        ClinicalDocumentRules.requireIssuable("visit_summary", Map.of("diagnosis", "d"));
    }

    @Test
    void certificatePeriodMustNotEndBeforeItStarts() {
        assertThatThrownBy(() -> ClinicalDocumentRules.requireIssuable("certificate",
                Map.of("purpose", "p", "restFrom", "2026-02-10", "restTo", "2026-02-01")))
                .isInstanceOf(ApiException.class);
        ClinicalDocumentRules.requireIssuable("certificate",
                Map.of("purpose", "p", "restFrom", "2026-02-01", "restTo", "2026-02-10"));
    }
}
