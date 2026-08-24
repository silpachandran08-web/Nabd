package com.nabd.hms.nursing.dto;

import jakarta.validation.constraints.NotBlank;

public record ProcedureConsentRequest(@NotBlank String signedName) {
}
