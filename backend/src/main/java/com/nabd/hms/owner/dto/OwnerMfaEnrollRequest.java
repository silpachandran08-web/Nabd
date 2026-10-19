package com.nabd.hms.owner.dto;

import jakarta.validation.constraints.NotBlank;

public record OwnerMfaEnrollRequest(@NotBlank String setupToken) {
}
