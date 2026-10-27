package com.nabd.hms.billing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * FIN-003: a catalogue line is chargeCode + quantity (+ optional override); the server resolves name, category, price
 * and tax. chargeName/category/unitPrice/taxRatePercent are read ONLY for a free-text line (no active catalogue row),
 * which needs billing.discount; for a catalogue line they are ignored.
 * PHL-001: prescriptionItemId links the line to a signed prescription line; required when the catalogue item is_rx.
 */
public record LineItemRequest(
        @NotBlank String chargeCode,
        String chargeName,
        String category,
        @Min(1) int quantity,
        @DecimalMin(value = "0") BigDecimal unitPrice,
        @DecimalMin(value = "0") BigDecimal taxRatePercent,
        @Valid Override override,
        UUID prescriptionItemId,
        /** PHL-018: "dispense anyway" for a stocked item when the shelf holds less than {@code quantity}; the missing units become a stock discrepancy. */
        @jakarta.validation.constraints.Size(max = 200) String shortfallReason
) {
    /** Existing callers: no shortfall reason. */
    public LineItemRequest(String chargeCode, String chargeName, String category, int quantity, BigDecimal unitPrice,
                           BigDecimal taxRatePercent, Override override, UUID prescriptionItemId) {
        this(chargeCode, chargeName, category, quantity, unitPrice, taxRatePercent, override, prescriptionItemId, null);
    }

    public record Override(@DecimalMin(value = "0") BigDecimal unitPrice, String reasonCode, String note) {
    }

    /** Server-built lines (package sales): no override. */
    public LineItemRequest(String chargeCode, String chargeName, String category, int quantity,
                           BigDecimal unitPrice, BigDecimal taxRatePercent) {
        this(chargeCode, chargeName, category, quantity, unitPrice, taxRatePercent, null, null, null);
    }
}
