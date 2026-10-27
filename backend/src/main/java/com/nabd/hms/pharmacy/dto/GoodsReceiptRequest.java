package com.nabd.hms.pharmacy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** PHL-007: a direct supplier bill. There is no supplier master yet, so the supplier is named; supplierId is kept as given. */
@Schema(name = "GoodsReceiptRequest")
public record GoodsReceiptRequest(
        UUID supplierId,
        @NotBlank @Size(max = 200) String supplierName,
        @NotBlank @Size(max = 60) String billNumber,
        @NotNull LocalDate billDate,
        @NotEmpty @Size(max = 200) List<@Valid Line> lines
) {
    @Schema(name = "GoodsReceiptLineRequest")
    public record Line(
            @NotNull UUID itemId,
            @NotBlank @Size(max = 60) String batchNo,
            @NotNull LocalDate expiryDate,
            @NotNull @PositiveOrZero BigDecimal mrp,
            @NotNull @PositiveOrZero BigDecimal rate,
            @Positive int qty,
            @PositiveOrZero Integer freeQty,
            @PositiveOrZero BigDecimal taxRate
    ) {
        public int freeQtyOrZero() {
            return freeQty == null ? 0 : freeQty;
        }

        public BigDecimal taxRateOrZero() {
            return taxRate == null ? BigDecimal.ZERO : taxRate;
        }
    }
}
