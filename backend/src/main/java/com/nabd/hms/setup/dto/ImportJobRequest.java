package com.nabd.hms.setup.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ImportJobRequest(
        @NotBlank @Pattern(regexp = "patients|appointments|invoices|charges") String importType,
        @NotBlank String fileName
) {
}
