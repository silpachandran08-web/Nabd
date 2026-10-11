package com.nabd.hms.clinical.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A correction is a partial update (only the fields sent change) and always needs a reason; the
 * original values are kept in the allergy history and the audit trail. */
public record AllergyCorrectionRequest(
        @Size(max = 200) String substance,
        @Pattern(regexp = "drug|food|environmental|other") String category,
        @Pattern(regexp = "mild|moderate|severe") String severity,
        @Size(max = 500) String reaction,
        @Pattern(regexp = "clinician|patient_reported|outside_record") String source,
        @NotBlank @Size(max = 500) String reason) {
}
