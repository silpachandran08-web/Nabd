package com.nabd.hms.clinical.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Severity is mandatory (the column is NOT NULL and an unknown severity must never be guessed). Category
 * and source are optional so older callers keep working; the register UI always sends both.
 * CLN-021: allergenIngredientId / allergenClassId (optional, drug master ids) make the allergy match coded drugs by ingredient
 * or class; they must exist in the drug master (404 DRUG_NOT_FOUND). */
public record AllergyWriteRequest(
        @NotBlank @Size(max = 200) String substance,
        @Pattern(regexp = "drug|food|environmental|other") String category,
        @NotBlank @Pattern(regexp = "mild|moderate|severe") String severity,
        @Size(max = 500) String reaction,
        @Pattern(regexp = "clinician|patient_reported|outside_record") String source,
        @Size(max = 80) String allergenIngredientId,
        @Size(max = 80) String allergenClassId) {

    /** Pre-CLN-021 shape: a free-text allergy without drug master codes. */
    public AllergyWriteRequest(String substance, String category, String severity, String reaction, String source) {
        this(substance, category, severity, reaction, source, null, null);
    }
}
