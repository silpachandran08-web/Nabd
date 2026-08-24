package com.nabd.hms.packages.dto;

import java.time.Instant;

public record InstanceEventResponse(String eventType, String note, Integer delta, String actorName, Instant createdAt) {
}
