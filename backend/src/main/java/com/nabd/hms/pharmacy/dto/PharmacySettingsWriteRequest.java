package com.nabd.hms.pharmacy.dto;

import jakarta.validation.constraints.Pattern;

public record PharmacySettingsWriteRequest(
        @Pattern(regexp = "external|hybrid|in_house") String mode
) {
}
