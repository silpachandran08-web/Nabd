package com.nabd.hms.packages.dto;

import java.time.Instant;

/** @param deliveryStatus COM-044: for a message event, requested | blocked | delivered | read | failed | cancelled | expired; else null */
public record InstanceEventResponse(String eventType, String note, Integer delta, String actorName, Instant createdAt, String deliveryStatus) {
}
