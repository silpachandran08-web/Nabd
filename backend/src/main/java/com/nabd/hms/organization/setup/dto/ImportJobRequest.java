package com.nabd.hms.organization.setup.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/**
 * fileName is required for the legacy stub job; uploadId (ADM-012) starts the staged pipeline and takes the name from the upload.
 * ADM-013: the staged pipeline takes patients, visit_history, drugs and stock; source names the old system whose standard
 * export this is (omitted or 'csv' = a generic file, headings matched by synonyms).
 */
public record ImportJobRequest(
        @NotBlank @Pattern(regexp = "patients|appointments|invoices|charges|visit_history|drugs|stock") String importType,
        String fileName,
        UUID uploadId,
        @Pattern(regexp = "xlsx|csv|zip") String sourceFormat,
        @Pattern(regexp = "practo_ray|clinicea|lybrate|medas|csv") String source
) {
}
