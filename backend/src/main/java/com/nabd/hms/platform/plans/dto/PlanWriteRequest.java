package com.nabd.hms.platform.plans.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

// Same request shape for create and edit (active toggle included) — mirrors ChargeHeadWriteRequest's
// reuse pattern in the tenant-side setup module.
public record PlanWriteRequest(
        @NotBlank String code,
        @NotBlank String name,
        @NotNull @Min(0) Integer monthlyPriceCents,
        @NotBlank @Pattern(regexp = "INR|SAR") String currency,
        @NotNull @Min(1) Integer seatLimit,
        boolean active
) {
}
