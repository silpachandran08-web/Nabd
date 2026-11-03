package com.nabd.hms.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** PHL-011: a customer's return of sealed medicines against the original bill (a FIN-013 refund request with maker-checker). */
public final class PharmacyReturnDtos {

    private PharmacyReturnDtos() {
    }

    /** @param refundMethod optional: must be a method the bill was paid with (the refund goes back the way it came); omitted = the bill's payments in order */
    @Schema(name = "PharmacyReturnRequest")
    public record ReturnRequest(@NotNull UUID invoiceId, @NotEmpty @Size(max = 100) @Valid List<ReturnLine> lines,
                                @Pattern(regexp = "cash|card|upi|other") String refundMethod, @Size(max = 500) String note) {
    }

    /** @param condition 'sealed', or 'opened' (always refused: only sealed packs come back) */
    @Schema(name = "PharmacyReturnLine")
    public record ReturnLine(@NotNull UUID invoiceLineId, @Min(1) @Max(100_000) int qty, @NotNull @Pattern(regexp = "sealed|opened") String condition) {
    }

    /** 201: the return waits for a second person's approval (POST /v1/billing/refund-requests/{refundId}/approve, billing:approve);
     * the approval issues the credit note, pays the refund and puts the units back into their batch in one transaction. */
    @Schema(name = "PharmacyReturnResponse")
    public record ReturnResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID refundId,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal amount,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currency,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<RefundRequestRequest.RefundTarget> refundTo) {
    }

    /** The bill as the return screen shows it. {@code pendingRefundId}: a refund of this bill already awaits approval (a new return is 409). */
    @Schema(name = "ReturnableBill")
    public record Bill(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID invoiceId,
                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String invoiceNumber,
                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currency,
                       UUID pendingRefundId,
                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> refundMethods,
                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<BillLine> lines) {
    }

    /** {@code allowed} false = the line cannot be returned and {@code reason} says why. */
    @Schema(name = "ReturnableBillLine")
    public record BillLine(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID invoiceLineId,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String chargeCode,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int soldQty,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int returnedQty,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int returnableQty,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal unitPrice,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal taxRatePercent,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean allowed,
                           String reason) {
    }
}
