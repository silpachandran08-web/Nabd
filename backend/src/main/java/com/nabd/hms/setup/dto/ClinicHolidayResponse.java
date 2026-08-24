package com.nabd.hms.setup.dto;

import java.time.LocalDate;

public record ClinicHolidayResponse(
        String id,
        LocalDate holidayDate,
        String name,
        boolean recurring
) {
}
