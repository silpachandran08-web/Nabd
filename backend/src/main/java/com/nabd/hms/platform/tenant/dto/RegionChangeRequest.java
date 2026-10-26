package com.nabd.hms.platform.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RegionChangeRequest(@NotBlank @Pattern(regexp = "^(IN|AE|SA|QA)$") String regionCode) {
}
