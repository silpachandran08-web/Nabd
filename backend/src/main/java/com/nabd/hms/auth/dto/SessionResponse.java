package com.nabd.hms.auth.dto;

import java.time.Instant;
import java.util.UUID;

public record SessionResponse(UUID id, String device, String ip, Instant lastSeenAt, boolean current) {
}
