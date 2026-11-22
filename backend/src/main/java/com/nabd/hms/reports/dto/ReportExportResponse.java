package com.nabd.hms.reports.dto;

import java.util.UUID;

/** ADM-047: the finished file, Base64, with the audit-recorded row count. Nothing is stored server side. */
public record ReportExportResponse(UUID exportId, String reportId, String format, String fileName, String contentType, int rows,
                                   boolean masked, String contentBase64) {
}
