package com.nabd.hms.staff.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** note is accepted for the UI but never stored in the audit row (free text may hold personal data). */
public record MfaResetRequest(@NotNull @Pattern(regexp = "lost_device|left_clinic|other") String reasonCode, @Size(max = 500) String note) {
}
