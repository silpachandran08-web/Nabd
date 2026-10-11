package com.nabd.hms.pharmacy.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Exactly one of {@code delta} (relative change, +/-) or {@code setTo} (absolute count after a stock-take) is required.
 * A reason is always required: every stock change becomes an immutable movement row and an audit event. */
public record PharmacyStockAdjustmentRequest(
        @Min(-1_000_000) @Max(1_000_000) Integer delta,
        @Min(0) @Max(10_000_000) Integer setTo,
        @NotBlank @Size(max = 500) String reason
) {
}
