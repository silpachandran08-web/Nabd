package com.nabd.hms.clinical.documents;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** DOC-030: rows and wire shapes for clinical documents (visit summary, referral letter, certificate). */
public final class ClinicalDocumentModels {

    private ClinicalDocumentModels() {
    }

    public record DocumentRow(UUID id, UUID patientId, UUID queueEntryId, UUID doctorId, String type, String status,
                       String bodyJson, String headerJson, UUID createdBy, UUID issuedBy, Instant issuedAt,
                       Instant createdAt, Instant updatedAt) {
    }

    /** The queue entry's own patient/doctor and token date - looked up server-side, never taken from the client. */
    public record VisitInfo(UUID patientId, UUID doctorId, String queueDate) {
    }

    public record PartyInfo(String name, String role) {
    }

    public record PatientHeader(String name, String mrn, String dob, String gender) {
    }

    /** Signed-note and prescription values used to prefill a visit summary (nulls where nothing exists). */
    public record SummarySources(boolean noteSigned, String diagnosis, String assessment, String plan,
                          boolean prescriptionSigned, java.util.List<String> medicines) {
    }

    // ── wire DTOs ────────────────────────────────────────────────────────────

    public record CreateRequest(String type, Map<String, String> body) {
    }

    public record UpdateRequest(Map<String, String> body) {
    }

    /** Header is null while a draft; set once at issue time and never changes afterwards. */
    public record DocumentResponse(UUID id, UUID patientId, UUID queueEntryId, UUID doctorId, String type,
                                   String status, Map<String, String> body, Map<String, Object> header,
                                   UUID issuedBy, Instant issuedAt, Instant createdAt, Instant updatedAt) {
    }

    /** Values the visit-summary form may start from. Only signed sources are used; the doctor edits before issuing. */
    public record SummaryPrefillResponse(boolean noteSigned, boolean prescriptionSigned, Map<String, String> body) {
    }
}
