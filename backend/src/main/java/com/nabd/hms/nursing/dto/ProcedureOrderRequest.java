package com.nabd.hms.nursing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ProcedureOrderRequest(@NotNull UUID queueEntryId, @NotBlank String chargeCode, String prepNotes, String consentNote) {
}
