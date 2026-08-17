package com.nabd.hms.owner.dto;

import java.util.List;
import java.util.UUID;

public record BrandWorkspaceResponse(UUID id, String name, String status, List<ClinicSummaryResponse> clinics) {
}
