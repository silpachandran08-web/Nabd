package com.nabd.hms.lab;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** PHL-042: reference ranges and critical limits of a lab test, in the test's own unit. */
public final class LabRangeDtos {

    /** 150 years in days: no band starts or ends later. */
    static final int MAX_AGE_DAYS = 55000;

    private LabRangeDtos() {
    }

    /**
     * One band. sex: M, F or any. The age band includes ageMinDays and excludes ageMaxDays (null = no upper age). Every limit is
     * optional, but a band needs at least one limit or a text range. A value below low is L, above high is H; a value at or
     * beyond a critical limit is critical.
     */
    @Schema(name = "LabRangeBand")
    public record RangeBand(@NotNull @Pattern(regexp = "^(M|F|any)$") String sex,
                            @NotNull @Min(0) @Max(MAX_AGE_DAYS) Integer ageMinDays,
                            @Min(1) @Max(MAX_AGE_DAYS) Integer ageMaxDays,
                            @Digits(integer = 9, fraction = 6) BigDecimal low,
                            @Digits(integer = 9, fraction = 6) BigDecimal high,
                            @Digits(integer = 9, fraction = 6) BigDecimal criticalLow,
                            @Digits(integer = 9, fraction = 6) BigDecimal criticalHigh,
                            @Size(max = 200) @Pattern(regexp = LabDtos.TEXT) String text) {
    }

    @Schema(name = "LabRangeSetRequest")
    public record RangeSetRequest(@NotEmpty @Size(max = 100) List<@NotNull @Valid RangeBand> ranges) {
    }

    /** version 0 with no ranges: the test has no reference ranges yet. */
    @Schema(name = "LabRangeSet")
    public record RangeSet(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) int version,
                           Instant effectiveFrom,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<RangeBand> ranges) {
    }
}
