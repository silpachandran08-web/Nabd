package com.nabd.hms.platform.plans.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

// Same request shape for create and edit (active toggle included) — mirrors ChargeHeadWriteRequest's
// reuse pattern in the tenant-side setup module. ADM-034 fields are optional so existing callers keep working:
// modules (closed list; null on edit = unchanged), limits (seats/branches/visitsPerMonth; seatLimit stays accepted).
public record PlanWriteRequest(
        @NotBlank String code,
        @NotBlank String name,
        @NotNull @Min(0) Integer monthlyPriceCents,
        @NotBlank @Pattern(regexp = "INR|SAR|AED|QAR") String currency,
        @Min(1) Integer seatLimit,
        boolean active,
        String market,
        @Pattern(regexp = "per_clinic|per_doctor") String pricingBasis,
        List<String> modules,
        @jakarta.validation.Valid Limits limits,
        String onTrialExpiry
) {
    public record Limits(@Min(1) Integer seats, @Min(1) Integer branches, @Min(1) Integer visitsPerMonth) {
    }
}
