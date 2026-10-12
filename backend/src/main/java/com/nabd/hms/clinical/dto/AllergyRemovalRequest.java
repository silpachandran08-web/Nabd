package com.nabd.hms.clinical.dto;

import jakarta.validation.constraints.Size;

/** Blank/missing reason is rejected by AllergyService with the specific `reason-required` problem; only the
 * length is bean-validated (V1-014: the controller now applies @Valid, so the 500-char cap is enforced). */
public record AllergyRemovalRequest(@Size(max = 500) String reason) {
}
