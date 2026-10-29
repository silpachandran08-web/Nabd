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

/** PHL-010 request and response shapes: stock-take sessions and reasoned batch adjustments. */
public final class StockTakeDtos {

    private StockTakeDtos() {
    }

    /** The fixed reason list (FRD PHR-04); 'other' needs a note. */
    public static final String REASON_CODES = "damaged|count_correction|theft_loss|expired|other";

    /** location is required for scope 'location', scheduleClass for scope 'class'. */
    @Schema(name = "StockTakeStartRequest")
    public record StartRequest(@NotNull @Pattern(regexp = "all|location|class") String scope, @Size(max = 80) String location,
                               @Size(max = 40) String scheduleClass) {
    }

    @Schema(name = "StockTakeStarted")
    public record StartResponse(UUID stockTakeId, int lineCount) {
    }

    @Schema(name = "StockTakeCount")
    public record CountEntry(@NotNull UUID batchId, @NotNull @Min(0) @Max(10_000_000) Integer counted,
                             @Pattern(regexp = REASON_CODES) String reasonCode, @Size(max = 500) String note) {
    }

    @Schema(name = "StockTakeCountsRequest")
    public record CountsRequest(@NotEmpty @Size(max = 1000) List<@Valid CountEntry> counts) {
    }

    @Schema(name = "StockTakeCountsResponse")
    public record CountsResponse(int updated) {
    }

    /** approverStaffId + approverPin: the second signer, needed when a controlled batch has a variance. */
    @Schema(name = "StockTakePostRequest")
    public record PostRequest(UUID approverStaffId, @Size(max = 20) String approverPin) {
    }

    /** expectedQty = snapshotQty + movementsSinceSnapshot (up to the moment the line was counted); variance = counted - expected. */
    @Schema(name = "StockTakeLine")
    public record Line(UUID batchId, UUID itemId, String itemCode, String itemName, String scheduleClass, boolean controlled,
                       String batchNo, LocalDate expiryDate, String location, int snapshotQty, int movementsSinceSnapshot, int expectedQty,
                       Integer countedQty, Integer variance, String reasonCode, String note, Integer postedDelta) {
    }

    @Schema(name = "StockTakeSummary")
    public record Summary(UUID stockTakeId, String scope, String scopeValue, String status, Instant snapshotAt, String createdByName,
                          String postedByName, String approvedByName, Instant postedAt, int lineCount, int countedCount) {
    }

    @Schema(name = "StockTakeSheet")
    public record Sheet(Summary summary, List<Line> lines) {
    }

    @Schema(name = "StockAdjustmentLine")
    public record AdjustmentView(UUID batchId, UUID itemId, int delta, int qtyBefore, int qtyAfter, String reasonCode) {
    }

    /** value = net variance at purchase rate (a batch without a rate counts 0). */
    @Schema(name = "StockTakePosted")
    public record PostResponse(UUID stockTakeId, String status, List<AdjustmentView> adjustments, BigDecimal value, String postedByName,
                               String approvedByName) {
    }

    /** Exactly one of delta (a change) or setTo (the counted total of the batch). */
    @Schema(name = "BatchAdjustmentRequest")
    public record BatchAdjustRequest(@Min(-1_000_000) @Max(1_000_000) Integer delta, @Min(0) @Max(10_000_000) Integer setTo,
                                     @NotBlank @Pattern(regexp = REASON_CODES) String reasonCode, @Size(max = 500) String note,
                                     UUID approverStaffId, @Size(max = 20) String approverPin) {
    }

    @Schema(name = "BatchAdjustmentResponse")
    public record BatchAdjustResponse(UUID batchId, UUID itemId, int delta, int qtyBefore, int qtyAfter, String reasonCode, String makerName,
                                      String approverName) {
    }

    @Schema(name = "AdjustmentApprover")
    public record Approver(UUID staffId, String name) {
    }
}
