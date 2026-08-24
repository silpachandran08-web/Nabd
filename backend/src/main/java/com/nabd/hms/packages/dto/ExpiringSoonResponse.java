package com.nabd.hms.packages.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ExpiringSoonResponse(
        String instanceId, String patientName, String packageName, int quantityConsumed, int quantityTotal,
        LocalDate expiresOn, BigDecimal valueLeft, int alertTier, boolean reminderSentForTier
) {
}
