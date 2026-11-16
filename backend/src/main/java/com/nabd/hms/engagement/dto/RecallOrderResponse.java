package com.nabd.hms.engagement.dto;

import java.time.LocalDate;
import java.util.UUID;

public record RecallOrderResponse(UUID recallId, LocalDate dueDate, String status, Integer intervalDays, String reasonCode,
                                  UUID bookedAppointmentId) {
}
