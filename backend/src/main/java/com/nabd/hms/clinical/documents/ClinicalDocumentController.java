package com.nabd.hms.clinical.documents;

import com.nabd.hms.clinical.PrescriptionService;
import com.nabd.hms.common.RequestMeta;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.nabd.hms.clinical.documents.ClinicalDocumentModels.CreateRequest;
import static com.nabd.hms.clinical.documents.ClinicalDocumentModels.DocumentResponse;
import static com.nabd.hms.clinical.documents.ClinicalDocumentModels.SummaryPrefillResponse;
import static com.nabd.hms.clinical.documents.ClinicalDocumentModels.UpdateRequest;

/** DOC-030: visit outputs. Reads need clinical:view; drafting needs clinical:edit; issuing also needs to be the visit's doctor or hold clinical:approve (as signing a prescription does). */
@RestController
@RequestMapping("/v1/clinical")
public class ClinicalDocumentController {

    private final ClinicalDocumentService service;

    ClinicalDocumentController(ClinicalDocumentService service) {
        this.service = service;
    }

    @com.nabd.hms.shared.phiread.PhiRead("visit_documents")
    @GetMapping("/visits/{queueEntryId}/documents")
    @PreAuthorize("hasAuthority('clinical:view')")
    public List<DocumentResponse> listForVisit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId) {
        return service.listForVisit(tenantId(jwt), queueEntryId);
    }

    @com.nabd.hms.shared.phiread.PhiRead("document_prefill")
    @GetMapping("/visits/{queueEntryId}/documents/summary-prefill")
    @PreAuthorize("hasAuthority('clinical:view')")
    public SummaryPrefillResponse summaryPrefill(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId) {
        return service.summaryPrefill(tenantId(jwt), queueEntryId);
    }

    @PostMapping("/visits/{queueEntryId}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('clinical:edit')")
    public DocumentResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId,
                                   @RequestBody CreateRequest req) {
        return service.create(tenantId(jwt), staffId(jwt), queueEntryId, req);
    }

    @com.nabd.hms.shared.phiread.PhiRead("document")
    @GetMapping("/documents/{id}")
    @PreAuthorize("hasAuthority('clinical:view')")
    public DocumentResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(tenantId(jwt), id);
    }

    @PatchMapping("/documents/{id}")
    @PreAuthorize("hasAuthority('clinical:edit')")
    public DocumentResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody UpdateRequest req) {
        return service.updateDraft(tenantId(jwt), id, req);
    }

    @PostMapping("/documents/{id}/issue")
    @PreAuthorize("hasAuthority('clinical:edit')")
    public DocumentResponse issue(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest http) {
        return service.issue(tenantId(jwt), staffId(jwt), canApprove(jwt), RequestMeta.clientIp(http), id);
    }

    private static boolean canApprove(Jwt jwt) {
        List<String> permissions = jwt.getClaimAsStringList("permissions");
        return permissions != null && permissions.contains(PrescriptionService.PERMISSION_CLINICAL_APPROVE);
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
