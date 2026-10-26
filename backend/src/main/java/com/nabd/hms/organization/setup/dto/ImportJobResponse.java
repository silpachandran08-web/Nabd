package com.nabd.hms.organization.setup.dto;

import java.time.Instant;

/** ADM-012: rowsTotal/rowsDone (commit progress), diff (ready/duplicates/errors, then created/merged/skipped/errors) and mapping (headers, columns, dateFormat) are set by the staged pipeline only. */
public record ImportJobResponse(
        String id,
        String importType,
        String fileName,
        String status,
        String resultUrl,
        String errorMessage,
        Instant createdAt,
        Integer rowsTotal,
        Integer rowsDone,
        Object diff,
        Object mapping
) {
    public ImportJobResponse(String id, String importType, String fileName, String status, String resultUrl, String errorMessage, Instant createdAt) {
        this(id, importType, fileName, status, resultUrl, errorMessage, createdAt, null, null, null, null);
    }
}
