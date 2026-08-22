package com.nabd.hms.packages.dto;

import jakarta.validation.constraints.NotBlank;

public record RefundRequestRequest(@NotBlank String reason) {
}
