package com.nabd.hms.engagement.dto;

import java.time.LocalDate;
import java.util.UUID;

/** CLN-043: an open vaccine recall (source 'vaccine', COM-053) of a patient booked with or arrived for the caller today. */
public record VaccineDueResponse(UUID recallId, UUID patientId, LocalDate dueDate, String reasonCode, boolean arrived) {
}
