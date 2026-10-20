package com.nabd.hms.setup.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ExportJobRequest(
        @NotBlank @Pattern(regexp = "full_tenant|patients|invoices|charges") String exportType
) {
}
