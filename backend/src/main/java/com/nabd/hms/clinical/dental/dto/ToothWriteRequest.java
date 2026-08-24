package com.nabd.hms.clinical.dental.dto;

import jakarta.validation.constraints.Pattern;

public record ToothWriteRequest(
        @Pattern(regexp = "healthy|decayed|filled|missing|crown|root_canal") String status,
        String note) {
}
