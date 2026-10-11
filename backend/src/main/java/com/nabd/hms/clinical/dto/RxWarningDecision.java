package com.nabd.hms.clinical.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The doctor's explicit decision on one safety warning of a prescription line: a named reason from the
 * fixed list for that kind (see RxSafetyChecks.reasonOptions). Who and when are stamped by the server. */
public record RxWarningDecision(@NotBlank @Pattern(regexp = "allergy|pregnancy|controlled") String kind,
                                @NotBlank String reasonCode,
                                @Size(max = 500) String note) {
}
