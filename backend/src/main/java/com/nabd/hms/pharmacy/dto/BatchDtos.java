package com.nabd.hms.pharmacy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** PHL-005 / PHL-006 request and response shapes. */
public final class BatchDtos {

    private BatchDtos() {
    }

    @Schema(name = "StockBatch")
    public record BatchResponse(UUID batchId, String batchNo, LocalDate expiryDate, BigDecimal mrp, BigDecimal purchaseRate,
                                int qtyOnHand, String status, String location, boolean expiryUnknown) {
    }

    @Schema(name = "StockBatchMovement")
    public record BatchMovementResponse(UUID id, String kind, int delta, int qtyBefore, int qtyAfter, String reason, Instant occurredAt) {
    }

    @Schema(name = "StockListItem")
    public record StockItemResponse(UUID itemId, String code, String name, String scheduleClass, int onHand, LocalDate nearestExpiry,
                                    int batchCount, boolean expiryUnknown, BigDecimal value) {
    }

    @Schema(name = "StockListPage")
    public record StockPageResponse(List<StockItemResponse> items, String nextCursor) {
    }

    /** expiryDate is a date (2027-06-30) or a month (2027-06, stored as the last day of that month). */
    @Schema(name = "StockBatchPatchRequest")
    public record BatchPatchRequest(@Size(max = 80) String batchNo, @Size(max = 10) String expiryDate, @Size(max = 80) String location,
                                    @NotBlank @Size(min = 3, max = 200) String reason) {
    }

    @Schema(name = "FefoPickLineRequest")
    public record PickLineRequest(@NotNull UUID itemId, @Min(1) @Max(100000) int qty, @Min(1) @Max(3650) Integer courseDays) {
    }

    @Schema(name = "FefoPickRequest")
    public record PickRequest(@NotEmpty @Size(max = 50) List<@Valid PickLineRequest> lines) {
    }

    @Schema(name = "FefoPickEntry")
    public record PickEntry(UUID batchId, int qty, LocalDate expiryDate, String warning) {
    }

    @Schema(name = "FefoPickLine")
    public record PickLineResponse(UUID itemId, List<PickEntry> picks) {
    }

    @Schema(name = "FefoPickResponse")
    public record PickResponse(List<PickLineResponse> lines) {
    }
}
