package com.nabd.hms.setup.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;
import java.util.UUID;

public record LicenceWriteRequest(
        @NotBlank @Pattern(regexp = "clinician|facility") String licenceType,
        UUID holderId,
        String holderName,
        @NotBlank String number,
        String issuingBody,
        @NotNull LocalDate expiryDate,
        @NotBlank @Pattern(regexp = "IN|KSA") String region
) {
}
