package com.nabd.hms.nursing.dto;

import jakarta.validation.constraints.NotBlank;

public record RefuseRequest(@NotBlank String reason) {
}
