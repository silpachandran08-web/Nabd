package com.nabd.hms.platform.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record OperatorLoginRequest(
        @NotBlank @Email String email,
        @NotBlank @Pattern(regexp = "^[0-9]{4,6}$") String pin
) {
}
