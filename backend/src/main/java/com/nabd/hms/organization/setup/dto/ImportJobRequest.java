package com.nabd.hms.organization.setup.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/** fileName is required for the legacy stub job; uploadId (ADM-012) starts the staged pipeline and takes the name from the upload. */
public record ImportJobRequest(
        @NotBlank @Pattern(regexp = "patients|appointments|invoices|charges") String importType,
        String fileName,
        UUID uploadId,
        @Pattern(regexp = "xlsx|csv") String sourceFormat
) {
}
