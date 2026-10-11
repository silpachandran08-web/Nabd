package com.nabd.hms.clinical.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AllergyRemovalRequest(@NotBlank @Size(max = 500) String reason) {
}
