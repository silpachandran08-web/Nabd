package com.nabd.hms.clinical.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Severity is mandatory (the column is NOT NULL and an unknown severity must never be guessed). Category
 * and source are optional so older callers keep working; the register UI always sends both. */
public record AllergyWriteRequest(
        @NotBlank @Size(max = 200) String substance,
        @Pattern(regexp = "drug|food|environmental|other") String category,
        @NotBlank @Pattern(regexp = "mild|moderate|severe") String severity,
        @Size(max = 500) String reaction,
        @Pattern(regexp = "clinician|patient_reported|outside_record") String source) {
}
