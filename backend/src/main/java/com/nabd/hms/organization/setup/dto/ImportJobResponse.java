package com.nabd.hms.organization.setup.dto;

import java.time.Instant;

public record ImportJobResponse(
        String id,
        String importType,
        String fileName,
        String status,
        String resultUrl,
        String errorMessage,
        Instant createdAt
) {
}
