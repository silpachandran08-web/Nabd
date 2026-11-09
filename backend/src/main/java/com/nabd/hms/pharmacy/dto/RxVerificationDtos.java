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

    @Schema(name = "RxVerificationLine")
    public record Line(UUID prescriptionItemId, String drug, BigDecimal qtyPrescribed, BigDecimal qtyDispensed, BigDecimal remaining) {
    }

    /** where: the clinic the billing invoice was raised at, with its number. */
    @Schema(name = "RxVerificationDispense")
    public record Dispense(Instant at, String where) {
    }

    /** status: valid | expired | dispensed | invalid. reason (invalid only): signature, other-tenant, not-found. An invalid answer
     * carries no prescription content. remaining: every line with what is still to give (empty once fully dispensed). */
    @Schema(name = "RxVerification")
    public record Response(String status, String reason, UUID prescriptionId, UUID queueEntryId, UUID worklistEntryId, Prescriber prescriber,
                           Instant signedAt, Instant validUntil, Dispense dispensed, List<Line> remaining) {
    }
}
