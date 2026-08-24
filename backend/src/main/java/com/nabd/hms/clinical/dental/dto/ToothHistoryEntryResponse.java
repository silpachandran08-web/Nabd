package com.nabd.hms.clinical.dental.dto;

import java.time.Instant;

public record ToothHistoryEntryResponse(String actorName, String actorRole, String action, String before,
                                         String after, Instant occurredAt) {
}
