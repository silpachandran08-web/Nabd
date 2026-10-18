package com.nabd.hms.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** reasonText is stored on the grant for the Owner's review and never logged (only reasonCode is). */
public record BreakGlassActivateRequest(
        @NotBlank @Pattern(regexp = "unconscious_patient|life_threatening|no_treating_clinician_available|other") String reasonCode,
        @NotBlank @Size(min = 10, max = 500) String reasonText,
        UUID patientId) {
}
