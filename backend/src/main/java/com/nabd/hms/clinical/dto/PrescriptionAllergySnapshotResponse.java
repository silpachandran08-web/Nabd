package com.nabd.hms.clinical.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** DOC-R3: the allergy statement captured when the prescription was signed, exactly as the server-side sign gate saw
 * it. Same meaning as AllergyStatusResponse.state ("recorded" / "none_documented" / "not_assessed"); allergies are
 * the register's ACTIVE entries at that moment. documentedBy/Name/At describe the "no known allergies" statement and
 * are null unless state is "none_documented". Never recomputed: later register changes do not alter it. */
public record PrescriptionAllergySnapshotResponse(String state, List<Allergy> allergies, UUID documentedBy,
                                                   String documentedByName, Instant documentedAt) {

    public record Allergy(String substance, String severity, String reaction) {
    }
}
