package com.nabd.hms.clinical.documents;

import com.nabd.hms.iam.api.ActorDirectory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.clinical.ClinicalAuthority;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nabd.hms.clinical.documents.ClinicalDocumentModels.CreateRequest;
import static com.nabd.hms.clinical.documents.ClinicalDocumentModels.DocumentResponse;
import static com.nabd.hms.clinical.documents.ClinicalDocumentModels.DocumentRow;
import static com.nabd.hms.clinical.documents.ClinicalDocumentModels.PartyInfo;
import static com.nabd.hms.clinical.documents.ClinicalDocumentModels.PatientHeader;
import static com.nabd.hms.clinical.documents.ClinicalDocumentModels.SummaryPrefillResponse;
import static com.nabd.hms.clinical.documents.ClinicalDocumentModels.SummarySources;
import static com.nabd.hms.clinical.documents.ClinicalDocumentModels.UpdateRequest;
import static com.nabd.hms.clinical.documents.ClinicalDocumentModels.VisitInfo;

/**
 * DOC-030: visit outputs. A document is a DRAFT (editable, no header) until the doctor issues it; issuing takes a
 * snapshot of the clinic / doctor / patient / visit values into {@code header} so the printed document never
 * drifts, and from then on the row is immutable (service refuses, trigger in V54 is the backstop).
 * Content is only what the doctor typed - see {@link ClinicalDocumentRules}.
 */
@Service
public class ClinicalDocumentService {

    private static final Logger log = LoggerFactory.getLogger(ClinicalDocumentService.class);
    private static final TypeReference<Map<String, String>> BODY_TYPE = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> HEADER_TYPE = new TypeReference<>() { };

    private final ClinicalDocumentRepository repo;
    private final AuditService auditService;
    private final TenantContext tenantContext;
    private final ObjectMapper objectMapper;
    private final ClinicalAuthority clinicalAuthority;

    private final ActorDirectory actors;

    ClinicalDocumentService(ClinicalDocumentRepository repo, AuditService auditService, TenantContext tenantContext,
                            ObjectMapper objectMapper, ClinicalAuthority clinicalAuthority, ActorDirectory actors) {
        this.actors = actors;
        this.clinicalAuthority = clinicalAuthority;
        this.repo = repo;
        this.auditService = auditService;
        this.tenantContext = tenantContext;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<DocumentResponse> listForVisit(UUID tenantId, UUID queueEntryId) {
        tenantContext.set(tenantId);
        repo.findVisit(tenantId, queueEntryId).orElseThrow(this::notFound);
        return repo.listForVisit(tenantId, queueEntryId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public DocumentResponse get(UUID tenantId, UUID id) {
        tenantContext.set(tenantId);
        return repo.find(tenantId, id).map(this::toResponse).orElseThrow(this::notFound);
    }

    @Transactional
    public SummaryPrefillResponse summaryPrefill(UUID tenantId, UUID queueEntryId) {
        tenantContext.set(tenantId);
        repo.findVisit(tenantId, queueEntryId).orElseThrow(this::notFound);
        SummarySources src = repo.findSummarySources(tenantId, queueEntryId);
        Map<String, String> body = new LinkedHashMap<>();
        putIfFilled(body, "diagnosis", src.diagnosis());
        putIfFilled(body, "assessment", src.assessment());
        putIfFilled(body, "plan", src.plan());
        if (src.medicines() != null && !src.medicines().isEmpty()) {
            body.put("medicines", String.join("\n", src.medicines()));
        }
        return new SummaryPrefillResponse(src.noteSigned(), src.prescriptionSigned(), body);
    }

    @Transactional
    public DocumentResponse create(UUID tenantId, UUID staffId, UUID queueEntryId, CreateRequest req) {
        tenantContext.set(tenantId);
        VisitInfo visit = repo.findVisit(tenantId, queueEntryId).orElseThrow(this::notFound);
        Map<String, String> body = ClinicalDocumentRules.sanitise(req.type(), req.body());
        UUID id = repo.insert(tenantId, visit, queueEntryId, req.type(), toJson(body), staffId);
        return repo.find(tenantId, id).map(this::toResponse).orElseThrow(this::notFound);
    }

    @Transactional
    public DocumentResponse updateDraft(UUID tenantId, UUID id, UpdateRequest req) {
        tenantContext.set(tenantId);
        DocumentRow row = repo.lock(tenantId, id).orElseThrow(this::notFound);
        if ("issued".equals(row.status())) {
            throw issued();
        }
        if (req.body() == null) {
            return toResponse(row); // no body supplied: leave the draft unchanged rather than wiping it
        }
        Map<String, String> body = ClinicalDocumentRules.sanitise(row.type(), req.body());
        if (repo.updateDraftBody(tenantId, id, toJson(body)) == 0) {
            throw issued();
        }
        return repo.find(tenantId, id).map(this::toResponse).orElseThrow(this::notFound);
    }

    @Transactional
    public DocumentResponse issue(UUID tenantId, UUID staffId, boolean callerCanApprove, String ip, UUID id) {
        tenantContext.set(tenantId);
        DocumentRow row = repo.lock(tenantId, id).orElseThrow(this::notFound);
        // Same gate as signing a prescription (V1-007 + CLN-005 licence rule): it prints under the visit doctor's name.
        String refusal = clinicalAuthority.refusal(tenantId, row.doctorId(), staffId, callerCanApprove);
        if (ClinicalAuthority.NOT_PRESCRIBER.equals(refusal)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "not-prescriber", "Prescriber only",
                    "Only the doctor on this visit, or a clinician with approval rights, can issue this document.");
        }
        if (refusal != null) {
            throw ClinicalAuthority.forbidden(refusal, "issue this document");
        }
        if ("issued".equals(row.status())) {
            throw issued();
        }
        Map<String, String> body = fromJson(row.bodyJson(), BODY_TYPE);
        ClinicalDocumentRules.requireIssuable(row.type(), body);

        PartyInfo issuer = actors.findActor(tenantId, staffId).map(a -> new PartyInfo(a.name(), a.role())).orElseThrow(this::notFound);
        String headerJson = toJson(buildHeader(tenantId, row, staffId, issuer));
        if (repo.issue(tenantId, id, staffId, headerJson) == 0) {
            throw issued();
        }
        // No clinical text in the audit row: who issued which kind of document for which visit.
        auditService.record(tenantId, "staff", staffId, issuer.name(), issuer.role(), ip,
                "clinical_document.issued", "clinical_document", id, null,
                Map.of("type", row.type(), "queueEntryId", row.queueEntryId().toString()));
        log.info("clinical document {} ({}) issued by {} (tenant {})", id, row.type(), staffId, tenantId);
        return repo.find(tenantId, id).map(this::toResponse).orElseThrow(this::notFound);
    }

    // ── header snapshot ──────────────────────────────────────────────────

    private Map<String, Object> buildHeader(UUID tenantId, DocumentRow row, UUID staffId, PartyInfo issuer) {
        VisitInfo visit = repo.findVisit(tenantId, row.queueEntryId()).orElseThrow(this::notFound);
        PatientHeader patient = repo.findPatientHeader(tenantId, row.patientId()).orElseThrow(this::notFound);
        PartyInfo doctor = actors.findActor(tenantId, row.doctorId()).map(a -> new PartyInfo(a.name(), a.role())).orElseThrow(this::notFound);

        Map<String, Object> clinic = new LinkedHashMap<>();
        clinic.put("name", repo.findClinicName(tenantId).orElse(null));
        clinic.put("region", repo.findRegion(tenantId).orElse(null));
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", row.doctorId().toString());
        doc.put("name", doctor.name());
        // Snapshot at issue time like every other header value; null when none is recorded (never a placeholder).
        doc.put("registrationNumber", repo.findRegistrationNo(tenantId, row.doctorId()).orElse(null));
        Map<String, Object> pat = new LinkedHashMap<>();
        pat.put("name", patient.name());
        pat.put("mrn", patient.mrn());
        pat.put("dob", patient.dob());
        pat.put("gender", patient.gender());
        Map<String, Object> visitMap = new LinkedHashMap<>();
        visitMap.put("queueEntryId", row.queueEntryId().toString());
        visitMap.put("date", visit.queueDate());
        Map<String, Object> by = new LinkedHashMap<>();
        by.put("id", staffId.toString());
        by.put("name", issuer.name());

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("clinic", clinic);
        header.put("doctor", doc);
        header.put("patient", pat);
        header.put("visit", visitMap);
        header.put("issuedBy", by);
        return header;
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private DocumentResponse toResponse(DocumentRow r) {
        Map<String, String> body = fromJson(r.bodyJson(), BODY_TYPE);
        Map<String, Object> header = r.headerJson() == null ? null : fromJson(r.headerJson(), HEADER_TYPE);
        return new DocumentResponse(r.id(), r.patientId(), r.queueEntryId(), r.doctorId(), r.type(), r.status(),
                body, header, r.issuedBy(), r.issuedAt(), r.createdAt(), r.updatedAt());
    }

    private static void putIfFilled(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) {
            m.put(k, v.strip());
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JacksonException e) {
            throw new IllegalStateException("could not serialise document json", e);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("stored document json is unreadable", e);
        }
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }

    private ApiException issued() {
        return new ApiException(HttpStatus.CONFLICT, "document-issued", "Document already issued",
                "This document has been issued and can no longer be changed.");
    }
}
