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
 * empty list rejected the same way, via that same service-level check. */
public record CheckoutRequest(
        @NotNull @Valid List<LineItemRequest> lineItems,
        @DecimalMin(value = "0") BigDecimal discount
) {
    public BigDecimal discountOrZero() {
        return discount == null ? BigDecimal.ZERO : discount;
    }
}
