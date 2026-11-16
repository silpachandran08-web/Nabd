package com.nabd.hms.organization.setup.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/** COM-024: the same shape as the response; semantic checks (a window that is empty, coordinates missing) answer 422 INVALID_WINDOW. */
public record MessagingSettingsRequest(@NotNull @Valid MessagingQuietHoursInput quietHours, @NotNull @Valid MessagingPrayerWindowsInput prayerWindows) {

    public record MessagingQuietHoursInput(@NotNull Boolean enabled,
                             @NotNull @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$") String start,
                             @NotNull @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$") String end) {
    }

    public record MessagingPrayerWindowsInput(@NotNull Boolean enabled, @NotNull String method, @NotNull Integer bufferMinutes,
                                @NotNull @Valid MessagingFridayBlockInput fridayBlock, BigDecimal latitude, BigDecimal longitude) {
    }

    public record MessagingFridayBlockInput(@NotNull @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$") String start,
                              @NotNull @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$") String end) {
    }
}
