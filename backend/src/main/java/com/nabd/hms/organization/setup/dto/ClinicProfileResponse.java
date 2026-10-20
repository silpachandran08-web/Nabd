package com.nabd.hms.setup.dto;

import java.time.Instant;
import java.util.List;

public record ClinicProfileResponse(
        String id,
        String name,
        String region,
        String timezone,
        String taxId,
        String taxIdType,
        String whatsappNumber,
        List<String> specialties,
        String status,
        Instant setupCompletedAt
) {
}
