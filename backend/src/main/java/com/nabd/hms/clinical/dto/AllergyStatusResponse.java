package com.nabd.hms.clinical.dto;

import java.time.Instant;
import java.util.UUID;

/** state: "recorded" (at least one active allergy), "none_documented" (a clinician documented no known
 * allergies) or "not_assessed" (nothing recorded and nobody has asked, which is NOT the same as none). */
public record AllergyStatusResponse(String state, int activeCount, UUID documentedBy, String documentedByName,
                                     Instant documentedAt) {
}
