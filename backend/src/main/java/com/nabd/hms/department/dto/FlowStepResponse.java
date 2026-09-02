package com.nabd.hms.department.dto;

import java.util.UUID;

public record FlowStepResponse(String stepType, UUID staffingDepartmentId, String staffingDepartmentName) {
}
