package com.nabd.hms.pharmacy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** PHL-009: quarantine, supplier returns (debit note), write-offs and the stock valuation split. */
public final class QuarantineDtos {

    private QuarantineDtos() {
    }

    /** reason: expired | damaged | other ('other' needs the note). The note stays in the audit trail, never in an event. */
    @Schema(name = "BatchQuarantineRequest")
    public record QuarantineRequest(@NotBlank @Pattern(regexp = "expired|damaged|other") String reason, @Size(max = 500) String note) {
    }

    /** value = quantity x purchase rate (0 when the batch has no rate). controlled: the item's class has a statutory register,
     * so writing it off needs a witness. supplierId is the supplier the batch was received from, when that was recorded. */
    @Schema(name = "QuarantinedBatch")
    public record QuarantinedBatch(UUID batchId, UUID itemId, String itemName, String batchNo, LocalDate expiryDate, int qtyOnHand,
                                   BigDecimal purchaseRate, BigDecimal value, UUID supplierId, boolean controlled, String status) {
    }

    @Schema(name = "QuarantineBin")
    public record Bin(List<QuarantinedBatch> items, BigDecimal totalValue) {
    }

    @Schema(name = "QuarantineLineRequest")
    public record LineRequest(@NotNull UUID batchId, @Min(1) @Max(10_000_000) int qty) {
    }

    /** There is no supplier master yet (PHL-007), so the supplier is named; supplierId is kept as given and, when the batch
     * recorded one at receipt, must match it. */
    @Schema(name = "SupplierReturnRequest")
    public record SupplierReturnRequest(UUID supplierId, @NotBlank @Size(max = 200) String supplierName,
                                        @NotEmpty @Size(max = 200) List<@Valid LineRequest> lines, @Size(max = 500) String reason) {
    }

    /** registerType: the statutory register of a controlled class on a write-off line; null otherwise. */
    @Schema(name = "QuarantineDocumentLine")
    public record DocumentLine(UUID batchId, UUID itemId, String itemName, String batchNo, LocalDate expiryDate, int qty, BigDecimal rate,
                               BigDecimal value, String registerType) {
    }

    /** The return note: debitNoteNumber is gapless per clinic and financial year; value = sum of qty x purchase rate. */
    @Schema(name = "SupplierReturn")
    public record SupplierReturn(UUID returnId, String debitNoteNumber, UUID supplierId, String supplierName, String status, BigDecimal value,
                                 String reason, String createdByName, Instant createdAt, List<DocumentLine> lines) {
    }

    /** method: destroyed | damaged | lost. A controlled-class line needs the witness: another staff member holding
     * pharmacy:adjust_approve, confirming with their own PIN. */
    @Schema(name = "StockWriteOffRequest")
    public record WriteOffRequest(@NotEmpty @Size(max = 200) List<@Valid LineRequest> lines,
                                  @NotBlank @Pattern(regexp = "destroyed|damaged|lost") String method, @Size(max = 500) String note,
                                  UUID witnessStaffId, @Size(max = 20) String witnessPin) {
    }

    @Schema(name = "StockWriteOff")
    public record WriteOff(UUID writeOffId, String method, BigDecimal value, String approvedByName, String witnessName,
                           List<DocumentLine> lines) {
    }

    /** At purchase rate. Saleable = active batches not past expiry; quarantined = everything else on hand (quarantined, or
     * expired and waiting for the nightly pass). */
    @Schema(name = "StockValuation")
    public record Valuation(LocalDate asOf, BigDecimal saleableValue, BigDecimal quarantinedValue, BigDecimal totalValue, int saleableBatches,
                            int quarantinedBatches) {
    }
}
