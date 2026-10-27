package com.nabd.hms.organization.setup.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** ADM-028: effect closed | short | open; {@code hours} (HH:mm windows) only with short. */
public record HolidayEffectRequest(@NotNull String effect, List<ClinicHolidayResponse.HolidayWindow> hours) {
}
