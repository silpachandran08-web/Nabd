package com.nabd.hms.pharmacy.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** PHL-012 worklist shapes. */
public final class WorklistDtos {

    private WorklistDtos() {
    }

    /** One in-house line. inStock: the item has usable stock (active, unexpired batches); false shows "out of stock" inline. */
    @Schema(name = "PharmacyWorklistLine")
    public record Line(UUID prescriptionItemId, String drug, BigDecimal qtyPrescribed, BigDecimal qtyDispensed, boolean inStock) {
    }

    /** status: new, in_progress, partially_dispensed, dispensed, cancelled or expired. */
    @Schema(name = "PharmacyWorklistEntry")
    public record Entry(UUID id, UUID prescriptionId, UUID queueEntryId, Integer token, String patientName, String doctorName,
                        Instant signedAt, String status, UUID claimedBy, String claimedByName, Instant validUntil, List<Line> lines) {
    }

    @Schema(name = "PharmacyWorklistPage")
    public record Page(List<Entry> items, String nextCursor) {
    }
}
