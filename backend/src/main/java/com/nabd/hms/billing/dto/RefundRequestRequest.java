package com.nabd.hms.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** FIN-013: either {@code lines} (credit note reverses those quantities) or {@code amount} (goodwill, tax-inclusive), never both. */
public record RefundRequestRequest(
        @Valid @Size(max = 200) List<RefundLine> lines,
        @DecimalMin("0.01") @Digits(integer = 10, fraction = 3) BigDecimal amount,
        @NotNull @Pattern(regexp = "[a-z][a-z0-9_]{1,39}") String reasonCode,
        @Size(max = 500) String note,
        @NotEmpty @Size(max = 10) @Valid List<RefundTarget> refundTo) {

    /** {@code resaleable}: a stocked pharmacy item goes back on the shelf only when this is true. */
    @Schema(name = "RefundLine")
    public record RefundLine(@NotNull UUID lineId, @Min(1) int quantity, Boolean resaleable) {
    }

    @Schema(name = "RefundTarget")
    public record RefundTarget(@NotNull UUID paymentId, @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 3) BigDecimal amount) {
    }
}
