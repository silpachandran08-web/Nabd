package com.nabd.hms.setup.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record SetupProgressUpdateRequest(
        @NotNull @Pattern(regexp = "skipped|done") String status
) {
}
