package com.nabd.hms.platform.provisioning.dto;

import java.time.Instant;

public record ProvisioningStepResponse(
        String stepName,
        int stepOrder,
        String status,
        Instant startedAt,
        Instant completedAt,
        String errorDetail
) {
}
