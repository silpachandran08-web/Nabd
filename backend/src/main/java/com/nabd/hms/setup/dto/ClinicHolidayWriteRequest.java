package com.nabd.hms.setup.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record ClinicHolidayWriteRequest(
        @NotNull LocalDate holidayDate,
        @NotBlank String name,
        boolean recurring
) {
}
