package com.nabd.hms.billing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record QuoteRequest(@NotEmpty @Valid List<LineItemRequest> lines, @Valid List<CheckoutRequest.DiscountRequest> discounts, String buyerGstin) {
}
