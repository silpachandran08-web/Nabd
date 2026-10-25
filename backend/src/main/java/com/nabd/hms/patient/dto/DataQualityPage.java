package com.nabd.hms.patient.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** ACC-028: GET /v1/patients/data-quality. rawPhone is the stored value as it was, so the desk can see what to fix. */
public record DataQualityPage(List<Item> items, String nextCursor) {
    @Schema(name = "DataQualityItem")
    public record Item(UUID patientId, String displayName, String rawPhone) {
    }
}
