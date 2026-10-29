package com.nabd.hms.queue.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record WorkingHoursWriteRequest(
        @NotNull @Min(0) @Max(6) Integer dayOfWeek,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        Integer slotMinutes,
        @Min(1) Integer maxPatients, // null = uncapped (NB-098)
        // ACC-011 (all optional; null = slot session on create, unchanged on update)
        @Pattern(regexp = "slot|token|hybrid") String sessionMode,
        @Min(0) Integer onlineCap,
        @Size(max = 60) String label,
        LocalDate effectiveFrom,
        // ACC-012: grid slot starts kept for walk-ins; null = unchanged
        List<LocalTime> reservedWalkInTimes,
        // ACC-012 walk-in rule; null = unchanged (defaults: interleave 1:3, grace 15)
        @Pattern(regexp = "interleave|reserved_only|none") String walkInMode,
        @Min(2) @Max(4) Integer walkInRatio,
        @Min(0) @Max(120) Integer lateGraceMinutes
) {
    public int slotMinutesOrDefault() {
        return slotMinutes == null ? 15 : slotMinutes;
    }
}
