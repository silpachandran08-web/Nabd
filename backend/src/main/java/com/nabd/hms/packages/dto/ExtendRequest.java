package com.nabd.hms.packages.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record ExtendRequest(@NotNull LocalDate newValidityEnd, @NotBlank String reason) {
}
