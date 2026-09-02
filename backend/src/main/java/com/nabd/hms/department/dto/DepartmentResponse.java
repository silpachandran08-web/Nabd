package com.nabd.hms.department.dto;

import java.util.UUID;

public record DepartmentResponse(UUID id, String name, boolean requiresVitals, boolean isDefault, boolean active) {
}
