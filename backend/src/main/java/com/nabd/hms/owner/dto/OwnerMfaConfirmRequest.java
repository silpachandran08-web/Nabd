package com.nabd.hms.owner.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record OwnerMfaConfirmRequest(@NotBlank String setupToken, @NotBlank @Pattern(regexp = "^[0-9]{6}$") String code) {
}
