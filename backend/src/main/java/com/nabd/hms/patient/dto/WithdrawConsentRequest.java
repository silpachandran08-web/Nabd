package com.nabd.hms.patient.dto;

import jakarta.validation.constraints.Pattern;

public record WithdrawConsentRequest(@Pattern(regexp = "treatment|data_processing|messaging|marketing") String consentType,
                                     @Pattern(regexp = "DESK|WHATSAPP|ASSISTED|PORTAL") String channel) {
    public String consentTypeOrDefault() {
        return consentType == null ? "data_processing" : consentType;
    }
}
