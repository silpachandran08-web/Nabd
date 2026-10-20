package com.nabd.hms.department.dto;

import java.util.UUID;

public record DepartmentResponse(UUID id, String name, boolean isDefault, boolean active) {
}
