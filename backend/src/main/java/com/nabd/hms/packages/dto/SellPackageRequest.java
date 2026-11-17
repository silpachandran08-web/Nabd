package com.nabd.hms.packages.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SellPackageRequest(
        @NotNull UUID patientId,
        @NotNull UUID packageId,
        @NotBlank String paymentMethod,
        /* COM-044: the doctor-set days between sessions; the patient is nudged to book the next one. Optional. */
        @Min(1) @Max(365) Integer nudgeIntervalDays
) {
}
