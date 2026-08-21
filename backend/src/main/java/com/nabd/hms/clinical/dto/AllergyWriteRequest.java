package com.nabd.hms.clinical.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AllergyWriteRequest(
        @NotBlank String substance,
        @Pattern(regexp = "mild|moderate|severe") String severity,
        String reaction) {
}
