package com.nabd.hms.queue.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record WorkingHoursResponse(UUID id, int dayOfWeek, LocalTime startTime, LocalTime endTime,
                                    int slotMinutes, Integer maxPatients, String sessionMode, Integer onlineCap,
                                    String label, LocalDate effectiveFrom, List<LocalTime> reservedWalkInTimes,
                                    String walkInMode, int walkInRatio, int lateGraceMinutes, boolean endsNextDay) {
}
