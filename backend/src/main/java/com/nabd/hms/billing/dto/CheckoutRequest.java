package com.nabd.hms.billing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;

import java.math.BigDecimal;
import java.util.List;

public record CheckoutRequest(
        @NotEmpty @Valid List<LineItemRequest> lineItems,
        @DecimalMin(value = "0") BigDecimal discount
) {
    public BigDecimal discountOrZero() {
        return discount == null ? BigDecimal.ZERO : discount;
    }
}
