package com.nabd.hms.packages.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** FIN-025: taxSplitEnabled and gstOpinionDecisionId are optional; a field that is not sent keeps its value ("" clears the decision id). */
public record PackageSettingsWriteRequest(
        @NotNull @DecimalMin(value = "0") @DecimalMax(value = "100") BigDecimal priceFloorPercent,
        Boolean taxSplitEnabled,
        @Size(max = 64) String gstOpinionDecisionId
) {
}
