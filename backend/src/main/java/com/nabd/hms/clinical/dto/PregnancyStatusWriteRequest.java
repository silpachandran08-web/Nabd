package com.nabd.hms.clinical.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;

/** An explicit clinician statement. "not_documented" is not accepted: it is the absence of a statement and
 * cannot be written. expectedDeliveryDate is only valid with status pregnant. */
public record PregnancyStatusWriteRequest(
        @NotBlank @Pattern(regexp = "not_pregnant|pregnant|lactating|unknown") String status,
        LocalDate expectedDeliveryDate) {
}
