package com.nabd.hms.organization.setup.dto;

import java.util.UUID;

public record StaffSummaryResponse(UUID id, String name, String roleName) {
}
