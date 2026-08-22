package com.nabd.hms.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UnlockRequest(@NotBlank @Pattern(regexp = "^[0-9]{4,6}$") String pin) {
}
