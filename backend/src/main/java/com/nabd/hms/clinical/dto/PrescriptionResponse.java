package com.nabd.hms.clinical.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** unresolvedWarnings: number of warnings that still need a recorded decision before this can be signed.
 * allergySnapshot (DOC-R3): the allergy statement captured at signing. Null for a draft, and null for a prescription
 * signed before the snapshot existed (V79); a signed prescription with a null snapshot must be shown as "allergy
 * status at signing not recorded", never filled in from the current register. */
public record PrescriptionResponse(UUID id, UUID queueEntryId, UUID patientId, UUID doctorId, String status,
                                    Instant createdAt, Instant signedAt, List<PrescriptionItemResponse> items,
                                    int unresolvedWarnings, PrescriptionAllergySnapshotResponse allergySnapshot) {
}
