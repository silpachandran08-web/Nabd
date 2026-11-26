package com.nabd.hms.clinical.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * IDC-055: the signed clinical record of one visit for the health-information exchange (FHIR mapping): the signed note with
 * its amendments and the signed prescription's lines. Drafts are never returned. A system read with no caller: no read-scope,
 * consent or field-grant check runs here and nothing is audited, so the calling flow must have authorised the disclosure and
 * must record it. Runs in the caller's tenant transaction.
 */
public interface SignedConsults {

    record Amendment(Instant at, UUID authorId, String reason, String subjective, String objective, String assessment, String plan, String diagnosis) {
    }

    /** @param authorId who signed (the visit's doctor when the signer was not recorded); @param amendments oldest first */
    record Note(UUID id, UUID patientId, UUID authorId, Instant signedAt, String subjective, String objective, String assessment, String plan,
                String diagnosis, List<Amendment> amendments) {
    }

    record RxLine(UUID id, String drugName, String brandName, String dosage, String sigPattern, String frequency, String duration, String route,
                  String instructions, BigDecimal quantity, String quantityUnit) {
    }

    /** @param prescriberId who signed (the visit's doctor when the signer was not recorded); @param lines in prescription order */
    record Prescription(UUID prescriberId, Instant signedAt, List<RxLine> lines) {
    }

    /** @param prescription null when the visit has no signed prescription */
    record Consult(Note note, Prescription prescription) {
    }

    /** Empty when the visit has no signed note in the bound tenant. */
    Optional<Consult> forVisit(UUID queueEntryId);
}
