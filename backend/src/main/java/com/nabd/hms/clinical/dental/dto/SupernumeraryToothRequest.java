package com.nabd.hms.clinical.dental.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

public record SupernumeraryToothRequest(
        @Min(11) @Max(48) int nearToothNumber,
        @Pattern(regexp = "healthy|decayed|filled|missing|crown|root_canal") String status,
        String note) {
}
