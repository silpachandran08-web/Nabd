package com.nabd.hms.reports.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** ADM-047: the same filters the report was run with, plus the file format. */
public record ReportExportRequest(@NotBlank @Pattern(regexp = "csv|xlsx|pdf") String format, LocalDate from, LocalDate to, UUID branch,
                                  BigDecimal threshold) {
}
