package com.nabd.hms.billing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(UUID id, String method, BigDecimal amount, Instant recordedAt) {
}
