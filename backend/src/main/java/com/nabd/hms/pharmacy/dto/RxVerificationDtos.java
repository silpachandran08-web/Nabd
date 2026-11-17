package com.nabd.hms.pharmacy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** PHL-013 Rx QR verification shapes. */
public final class RxVerificationDtos {

    private RxVerificationDtos() {
    }

    @Schema(name = "RxVerificationRequest")
    public record Request(@NotBlank @Size(max = 1000) String qrPayload) {
    }

    @Schema(name = "RxVerificationPrescriber")
    public record Prescriber(String name, String regNo) {
    }

    /** CLN-024: remaining counts every refill (qtyPrescribed per dispense, once plus once per refill); refillsAllowed = dispenses after
     * the first, refillsUsed = how many of them were given; validUntil = the line's own validity, null when it has none. */
    @Schema(name = "RxVerificationLine")
    public record Line(UUID prescriptionItemId, String drug, BigDecimal qtyPrescribed, BigDecimal qtyDispensed, BigDecimal remaining,
                       int refillsAllowed, int refillsUsed, Instant validUntil) {
    }

    /** where: the clinic the billing invoice was raised at, with its number. */
    @Schema(name = "RxVerificationDispense")
    public record Dispense(Instant at, String where) {
    }

    /** status: valid | expired | dispensed | invalid. reason (invalid only): signature, other-tenant, not-found, revoked (CLN-023). An invalid answer
     * carries no prescription content. remaining: every line with what is still to give (empty once fully dispensed). */
    @Schema(name = "RxVerification")
    public record Response(String status, String reason, UUID prescriptionId, UUID queueEntryId, UUID worklistEntryId, Prescriber prescriber,
                           Instant signedAt, Instant validUntil, Dispense dispensed, List<Line> remaining) {
    }

    /** CLN-023: what any phone sees for a scanned Rx verify link. Doctor, validity and counts only: no patient, no medicine, no diagnosis.
     * status: valid | expired | dispensed | revoked; dispensedAt only when dispensed. */
    @Schema(name = "RxPublicVerification")
    public record PublicVerification(String status, PublicDoctor doctor, Instant issuedAt, Instant validUntil, int lineCount, Instant dispensedAt) {
    }

    @Schema(name = "RxPublicDoctor")
    public record PublicDoctor(String name, String registrationNo, String council) {
    }
}
