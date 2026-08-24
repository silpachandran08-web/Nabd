package com.nabd.hms.setup.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

public record StaffShiftWriteRequest(
        @NotNull UUID staffId,
        @NotBlank String patternJson,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo
) {
}
