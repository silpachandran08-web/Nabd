package com.nabd.hms.clinical.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** unresolvedWarnings: number of warnings that still need a recorded decision before this can be signed.
 * allergySnapshot (DOC-R3): the allergy statement captured at signing. Null for a draft, and null for a prescription
 * signed before the snapshot existed (V79); a signed prescription with a null snapshot must be shown as "allergy
 * status at signing not recorded", never filled in from the current register.
 * warnings (DOC-031): flat list of the allergy matches across all lines, derived from the same per-line check as
 * items[].warnings; itemIndex is the 0-based position in items. Recomputed on every read.
 * safetyDictionaryVersion (CLN-004): the safety dictionary version active when it was signed (e.g. "IN v0");
 * null for a draft and for a prescription signed before V1211. */
public record PrescriptionResponse(UUID id, UUID queueEntryId, UUID patientId, UUID doctorId, String status,
                                    Instant createdAt, Instant signedAt, List<PrescriptionItemResponse> items,
                                    int unresolvedWarnings, PrescriptionAllergySnapshotResponse allergySnapshot,
                                    List<PrescriptionWarningResponse> warnings, String safetyDictionaryVersion,
                                    // CLN-005: stored signer and the registration number signed under; null on a draft
                                    UUID signedBy, String signedByRegistrationNo) {
}
