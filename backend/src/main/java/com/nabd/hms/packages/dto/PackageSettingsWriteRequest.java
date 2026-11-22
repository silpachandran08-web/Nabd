package com.nabd.hms.packages.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** FIN-025: taxSplitEnabled and gstOpinionDecisionId are optional; a field that is not sent keeps its value ("" clears the decision id). The id is a
 * decision-log reference (DEC-2026-014, T-213), never free text, so a pasted UUID or sentence is a 400. */
public record PackageSettingsWriteRequest(
        @NotNull @DecimalMin(value = "0") @DecimalMax(value = "100") BigDecimal priceFloorPercent,
        Boolean taxSplitEnabled,
        @Size(max = 64) @Pattern(regexp = "\\s*|\\s*(DEC|T)-[A-Za-z0-9-]{1,60}\\s*") String gstOpinionDecisionId
) {
}
