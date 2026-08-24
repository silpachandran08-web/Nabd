package com.nabd.hms.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record BreakGlassActivateRequest(@NotBlank String reason) {
}
