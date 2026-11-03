package com.nabd.hms.pharmacy.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Exactly one of {@code delta} (relative change, +/-) or {@code setTo} (absolute count after a stock-take) is required.
 * PHL-010: {@code reasonCode} is one of the fixed list (damaged, count_correction, theft_loss, expired, other); {@code reason} is the
 * note, required for 'other'. A request with only the older free-text {@code reason} is recorded as 'other' with that text.
 * Every stock change becomes an immutable movement row (with the code) and an audit event. */
public record PharmacyStockAdjustmentRequest(
        @Min(-1_000_000) @Max(1_000_000) Integer delta,
        @Min(0) @Max(10_000_000) Integer setTo,
        @Size(max = 500) String reason,
        @Pattern(regexp = StockTakeDtos.REASON_CODES) String reasonCode
) {
}
