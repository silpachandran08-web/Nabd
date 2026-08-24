package com.nabd.hms.queue.dto;

import java.time.LocalTime;
import java.util.UUID;

public record WorkingHoursResponse(UUID id, int dayOfWeek, LocalTime startTime, LocalTime endTime,
                                    int slotMinutes, Integer maxPatients) {
}
