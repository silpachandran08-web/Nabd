package com.nabd.hms.platform.access.dto;

import java.time.Instant;

public record DecisionResponse(String status, Instant windowEndsAt) {
}
