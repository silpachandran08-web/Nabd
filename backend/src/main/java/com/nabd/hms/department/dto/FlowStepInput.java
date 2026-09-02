package com.nabd.hms.department.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record FlowStepInput(
        @NotBlank @Pattern(regexp = "billing|vitals|consultation|procedures") String stepType,
        UUID staffingDepartmentId
) {
}
