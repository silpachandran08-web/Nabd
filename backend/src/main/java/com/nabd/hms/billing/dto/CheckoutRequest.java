package com.nabd.hms.billing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/** lineItems may be empty ONLY when extending an already-created invoice at a later billing
 * checkpoint (NB-355) — CheckoutService enforces "must be non-empty" itself for the first time a
 * visit is billed, since that rule is conditional and can't be expressed as a bean validation
 * annotation. otcCheckout/sellPackageInvoice (always a brand-new standalone invoice) still get an
 * empty list rejected the same way, via that same service-level check.
 * FIN-003: discounts[] are ledger rows (each needs a reason code). The scalar {@code discount} is the legacy form,
 * kept so a zero discount from older clients still parses; since FIN-018 a non-zero one is refused (422 REASON_REQUIRED),
 * because it carries no reason code.
 * FIN-018: discountApprovalId is the owner's approval (POST /v1/billing/discount-approvals) for a bill whose discounts and
 * below-list overrides are above the clinic's threshold; without it such a bill is refused with 409 APPROVAL_REQUIRED.
 * PHL-019: payments (counter sale only, POST /otc-checkout) settle the new invoice in the same transaction; they must add up
 * to its total (422 payment-mismatch) and need billing:edit like POST /invoices/{id}/payments. Ignored by the visit checkout. */
public record CheckoutRequest(
        @NotNull @Valid List<LineItemRequest> lineItems,
        @DecimalMin(value = "0") BigDecimal discount,
        @Valid List<DiscountRequest> discounts,
        String buyerGstin,
        java.util.UUID discountApprovalId,
        @Valid @jakarta.validation.constraints.Size(max = 4) List<PaymentRequest> payments
) {
    public CheckoutRequest(List<LineItemRequest> lineItems, BigDecimal discount) {
        this(lineItems, discount, null, null, null, null);
    }

    public CheckoutRequest(List<LineItemRequest> lineItems, BigDecimal discount, List<DiscountRequest> discounts, String buyerGstin) {
        this(lineItems, discount, discounts, buyerGstin, null, null);
    }

    public record DiscountRequest(@NotNull String kind, @NotNull @DecimalMin(value = "0.01") BigDecimal value,
                                  String reasonCode, String note) {
    }

    public CheckoutRequest(List<LineItemRequest> lineItems, BigDecimal discount, List<DiscountRequest> discounts, String buyerGstin,
                           java.util.UUID discountApprovalId) {
        this(lineItems, discount, discounts, buyerGstin, discountApprovalId, null);
    }

    public BigDecimal discountOrZero() {
        return discount == null ? BigDecimal.ZERO : discount;
    }

    public List<DiscountRequest> discountsOrEmpty() {
        return discounts == null ? List.of() : discounts;
    }
}
