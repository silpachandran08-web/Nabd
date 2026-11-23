package com.nabd.hms.billing.gst;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * FIN-011: the finished GSTR-1 file, Base64, in the same envelope as the ADM-047 report exports (the staff-web client reads JSON
 * only). {@code reconciled} is false when the sections do not add up to the invoice register: the file then says so on its first
 * sheet and must not be filed. Nothing is stored server side.
 */
@Schema(name = "Gstr1ExportResponse")
public record Gstr1ExportResponse(UUID exportId, String period, String format, String fileName, String contentType, boolean reconciled,
                                  int invoices, int creditNotes, String contentBase64) {
}
