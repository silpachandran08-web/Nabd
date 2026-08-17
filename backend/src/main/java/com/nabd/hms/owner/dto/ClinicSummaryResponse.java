package com.nabd.hms.owner.dto;

import java.util.UUID;

public record ClinicSummaryResponse(UUID id, String name, String slug, String region, String status) {
}
