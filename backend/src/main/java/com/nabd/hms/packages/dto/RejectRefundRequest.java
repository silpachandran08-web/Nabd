package com.nabd.hms.packages.dto;

import jakarta.validation.constraints.NotBlank;

public record RejectRefundRequest(@NotBlank String reason) {
}
