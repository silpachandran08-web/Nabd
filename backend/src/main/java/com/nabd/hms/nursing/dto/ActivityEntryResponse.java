package com.nabd.hms.nursing.dto;

import java.time.Instant;

public record ActivityEntryResponse(String kind, String activity, String patientName, Instant occurredAt) {
}
