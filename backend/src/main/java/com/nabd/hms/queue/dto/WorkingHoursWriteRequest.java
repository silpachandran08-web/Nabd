package com.nabd.hms.queue.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

public record WorkingHoursWriteRequest(
        @NotNull @Min(0) @Max(6) Integer dayOfWeek,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        Integer slotMinutes,
        @Min(1) Integer maxPatients // null = uncapped (NB-098)
) {
    public int slotMinutesOrDefault() {
        return slotMinutes == null ? 15 : slotMinutes;
    }
}
