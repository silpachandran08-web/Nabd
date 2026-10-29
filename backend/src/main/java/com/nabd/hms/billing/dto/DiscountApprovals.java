package com.nabd.hms.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** FIN-018: discount approval and leakage bodies. Money is minor units. */
public final class DiscountApprovals {

    private DiscountApprovals() {
    }

    /** The bill as the desk would issue it (same lines and discounts as POST /checkout/{queueEntryId}; overrides ride on the lines).
     * invoiceDraftId names the bill: the queue entry for a visit bill, any client-made id for a counter sale. */
    @Schema(name = "DiscountApprovalCreateRequest")
    public record CreateRequest(@NotNull UUID invoiceDraftId, @NotNull @Valid List<LineItemRequest> lineItems,
                                @Valid List<CheckoutRequest.DiscountRequest> discounts, String buyerGstin) {
        public CheckoutRequest cart() {
            return new CheckoutRequest(lineItems, null, discounts, buyerGstin, null);
        }
    }

    /** method 'pin': pinAssertion is the token of POST /v1/auth/pin-assertion, and the owner it names is the approver. */
    @Schema(name = "DiscountApprovalDecideRequest")
    public record DecideRequest(@NotBlank String decision, @NotBlank String method, String pinAssertion) {
    }

    /** billStatus: awaiting_approval (cannot be issued), approved (issue it with this approvalId), rejected, expired, issued. */
    @Schema(name = "DiscountApproval")
    public record Item(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID approvalId,
                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String billStatus,
                       UUID invoiceDraftId, UUID invoiceId,
                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long amountMinor,
                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal percent,
                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currency,
                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reasonCode,
                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID requestedBy,
                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String requestedByName,
                       UUID approverId, String approverName, String method,
                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
                       Instant decidedAt,
                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt,
                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean payLater,
                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CheckoutRequest cart) {
    }

    @Schema(name = "DiscountApprovalList")
    public record ListResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Item> items) {
    }

    /** type: discount | override (below list) | write_off | refund. approvalMethod: direct (applied by a holder of the grant,
     * below the threshold; the approver is that person), in_app or pin (owner approval), maker_checker (refund). */
    @Schema(name = "LeakageItem")
    public record LeakageItem(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String type,
                              @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                              @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant at,
                              @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID invoiceId,
                              String invoiceNumber,
                              @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long amountMinor,
                              @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reasonCode,
                              UUID appliedBy, String appliedByName, UUID approverId, String approverName,
                              @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String approvalMethod) {
    }

    /** ownerApprovedMinor: the part approved by the owner above the threshold; withoutOwnerApprovalMinor: everything else. */
    @Schema(name = "LeakageTotals")
    public record LeakageTotals(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) long discountMinor,
                                @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long overrideMinor,
                                @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long writeOffMinor,
                                @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long refundMinor,
                                @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long totalMinor,
                                @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long ownerApprovedMinor,
                                @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long withoutOwnerApprovalMinor,
                                @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int count) {
    }

    @Schema(name = "LeakageResponse")
    public record LeakageResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate from,
                                  @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate to,
                                  @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currency,
                                  @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<LeakageItem> items,
                                  @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LeakageTotals totals) {
    }
}
