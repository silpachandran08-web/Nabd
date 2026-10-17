package com.nabd.hms.patient.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** CGV-002: body of POST /v1/patients/{id}/guardian/review. Only "keep" exists; revoking is DELETE /guardian. */
public record GuardianReviewDecisionRequest(@NotNull @Pattern(regexp = "keep") String decision) {
}
