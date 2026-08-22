package com.nabd.hms.reports.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BillingLeakageResponse(String rule, BigDecimal thresholdAmount, List<Entry> entries) {

    public record Entry(UUID procedureOrderId, UUID queueEntryId, String patientName, String chargeCode,
                         String chargeName, BigDecimal amount, Instant completedAt) {
    }
}
