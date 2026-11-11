package com.nabd.hms.nursing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/** CLN-014: consentId / pdfDocumentId (the PDF's file-store id) / sha256 are null for a typed-name consent. Download: GET /v1/nursing/procedure-consents/{consentId}/pdf. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsentCaptureResponse(UUID consentId, UUID pdfDocumentId, String sha256, ProcedureOrderResponse order) {
}
