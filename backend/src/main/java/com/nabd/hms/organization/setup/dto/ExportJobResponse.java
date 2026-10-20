package com.nabd.hms.setup.dto;

import java.time.Instant;

public record ExportJobResponse(
        String id,
        String exportType,
        String status,
        String resultUrl,
        String errorMessage,
        Instant createdAt
) {
}
