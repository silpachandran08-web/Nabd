package com.nabd.hms.organization.setup.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;
import java.util.UUID;

public record LicenceWriteRequest(
        @NotBlank @Pattern(regexp = "clinician|facility|nurse|lab|pharmacist") String licenceType,
        UUID holderId,
        String holderName,
        @NotBlank String number,
        String issuingBody,
        @NotNull LocalDate expiryDate,
        @NotBlank @Pattern(regexp = "IN|AE|SA|QA") String region,
        /** ADM-032: the scanned certificate in the regional object store (file_objects); optional, kept when an edit omits it. */
        UUID certificateUploadId
) {
}
