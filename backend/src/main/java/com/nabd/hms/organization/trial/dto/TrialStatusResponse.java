package com.nabd.hms.organization.trial.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** status: the subscription's (trialing, active, free, cancelled ...) or "none" before the platform started one. blockers: dpa, plan. */
public record TrialStatusResponse(String status, Instant endsAt, Integer daysLeft, List<String> blockers, TrialConversion conversion) {

    @Schema(name = "TrialConversion")
    public record TrialConversion(UUID jobId, String status, long syntheticRowsDeleted) {
    }
}
