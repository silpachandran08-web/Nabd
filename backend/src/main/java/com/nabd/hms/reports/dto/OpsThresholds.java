package com.nabd.hms.reports.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * ADM-042: the owner's alert thresholds (PUT /v1/reports/live/thresholds, and echoed on the board). Minutes are 1..240.
 * labTatBreach is stored for the lab turnaround alert, which is not built yet (lab orders are PHL-043).
 */
public record OpsThresholds(@NotNull @Min(1) @Max(240) Integer pharmacyWaitMin, @NotNull Boolean labTatBreach,
                            @NotNull @Min(1) @Max(240) Integer requestWaitMin, @NotNull @Min(1) @Max(240) Integer doctorLateMin) {

    // shortcut: product defaults of the owner.ops prototype (pharmacy 8 min, requests 10 min); 15 min late is a product default, tune per clinic on the board
    public static final OpsThresholds DEFAULTS = new OpsThresholds(8, true, 10, 15);
}
