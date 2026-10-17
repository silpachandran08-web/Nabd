package com.nabd.hms.pharmacy.dto;

import jakarta.validation.constraints.Pattern;

public record PharmacySettingsWriteRequest(
        @Pattern(regexp = "external|hybrid|in_house") String mode,
        /** PHA-006: required (3-200 chars) when {@code mode} changes; checked in the service so it maps to reason-required. */
        String reason
) {
}
