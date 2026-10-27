package com.nabd.hms.organization.setup.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ApplyTemplateRequest(@NotNull @Min(2000) @Max(2100) Integer year) {
}
