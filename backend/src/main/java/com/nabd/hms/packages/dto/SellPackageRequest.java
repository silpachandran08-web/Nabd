package com.nabd.hms.packages.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SellPackageRequest(
        @NotNull UUID patientId,
        @NotNull UUID packageId,
        @NotBlank String paymentMethod
) {
}
