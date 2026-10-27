package com.nabd.hms.organization.setup.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/** {@code effect}: closed, short (then {@code hours} are the bookable windows) or open. {@code bookingsToMove}: booked appointments on a closed day, null otherwise (ADM-028). */
public record ClinicHolidayResponse(
        String id,
        LocalDate holidayDate,
        String name,
        boolean recurring,
        String effect,
        List<HolidayWindow> hours,
        String source,
        Integer bookingsToMove
) {

    @Schema(name = "HolidayWindow")
    public record HolidayWindow(String from, String to) {
    }
}
