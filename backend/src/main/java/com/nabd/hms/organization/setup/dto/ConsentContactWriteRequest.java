package com.nabd.hms.setup.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ConsentContactWriteRequest(
        @NotBlank String name,
        @NotBlank @Email String email,
        String phone
) {
}
