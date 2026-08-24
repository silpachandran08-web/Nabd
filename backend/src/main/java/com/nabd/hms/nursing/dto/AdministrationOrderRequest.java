package com.nabd.hms.nursing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record AdministrationOrderRequest(
        @NotNull UUID queueEntryId,
        @NotBlank String drugName,
        @NotBlank String dose,
        @Pattern(regexp = "IM|IV|SC|infusion|oral|topical") String route,
        String site
) {
}
