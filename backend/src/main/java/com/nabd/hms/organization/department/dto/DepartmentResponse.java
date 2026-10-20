package com.nabd.hms.organization.department.dto;

import java.util.UUID;

public record DepartmentResponse(UUID id, String name, boolean isDefault, boolean active) {
}
