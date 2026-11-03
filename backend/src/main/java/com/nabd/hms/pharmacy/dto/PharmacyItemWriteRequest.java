package com.nabd.hms.pharmacy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PharmacyItemWriteRequest(
        @NotBlank String name,
        boolean isRx,
        String hsnCode,
        @NotNull @PositiveOrZero BigDecimal price,
        @PositiveOrZero BigDecimal taxRatePercent,
        @PositiveOrZero Integer stockQty,
        /** PHL-002: must be valid for the tenant's market (422 schedule-not-valid-for-market). Omitted = unchanged (or derived from isRx on create). */
        @Size(max = 40) String scheduleClass,
        /** PHL-002: drug master product to link; its schedule flag sets the class unless scheduleClass is also sent. */
        @Size(max = 80) String drugMasterId,
        @Size(max = 200) String genericName,
        @Positive Integer packSize,
        @Size(max = 40) String unit,
        /** PHL-004: false = bought outside. Omitted = unchanged (true on create). */
        Boolean inHouse,
        /** PHL-021: true = the price already includes tax (India MRP pricing). Omitted = unchanged (the charge head's setting stands). */
        Boolean priceInclusiveOfTax,
        /** PHL-021: zero-rated medicine (VAT 0% with the reason on the invoice). Omitted = unchanged (false on create). */
        Boolean zeroRated,
        /** PHL-021: maximum retail price; a selling price above it is refused at billing (422 price-above-mrp). Omitted = unchanged. */
        @PositiveOrZero BigDecimal mrp,
        /** PHL-011: a cold-chain item is never taken back from a customer. Omitted = unchanged (false on create). */
        Boolean coldChain,
        /** PHL-011: false = customer returns are refused. Omitted = unchanged (true on create). */
        Boolean returnable
) {
    public PharmacyItemWriteRequest withTaxRatePercent(BigDecimal rate) {
        return new PharmacyItemWriteRequest(name, isRx, hsnCode, price, rate, stockQty, scheduleClass, drugMasterId, genericName, packSize,
                unit, inHouse, priceInclusiveOfTax, zeroRated, mrp, coldChain, returnable);
    }

    public BigDecimal taxRatePercentOrZero() {
        return taxRatePercent == null ? BigDecimal.ZERO : taxRatePercent;
    }

    public int stockQtyOrZero() {
        return stockQty == null ? 0 : stockQty;
    }
}
