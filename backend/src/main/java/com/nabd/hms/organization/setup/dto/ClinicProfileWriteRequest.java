package com.nabd.hms.setup.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public record ClinicProfileWriteRequest(
        @NotBlank String name,
        @NotBlank String timezone,
        @Pattern(regexp = "^GSTIN|VAT$") String taxIdType,
        String taxId,
        String whatsappNumber,
        List<String> specialties
) {
}
