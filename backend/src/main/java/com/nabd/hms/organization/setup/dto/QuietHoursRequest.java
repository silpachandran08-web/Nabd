package com.nabd.hms.organization.setup.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record QuietHoursRequest(@NotNull Boolean enabled,
                                @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$") String from,
                                @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$") String to) {
}
