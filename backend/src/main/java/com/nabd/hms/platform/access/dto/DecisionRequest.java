package com.nabd.hms.platform.access.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DecisionRequest(
        @NotNull @Pattern(regexp = "approve|decline") String decision,
        @Size(max = 500) String note
) {
}
