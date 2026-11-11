package com.nabd.hms.nursing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConsentWithdrawRequest(@NotBlank @Size(max = 500) String reason) {
}
