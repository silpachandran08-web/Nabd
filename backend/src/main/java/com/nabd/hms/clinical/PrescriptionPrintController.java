package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.PrescriptionPrintHeaderResponse;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.patient.api.SecureDocumentLinks;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/clinical")
public class PrescriptionPrintController {

    @Schema(name = "RxRevokeRequest")
    record RevokeRequest(@NotBlank @Size(min = 5, max = 500) String reason) {
    }

    @Schema(name = "RxRevokeResponse")
    record RevokeResponse(String status, Instant revokedAt) {
    }

    @Schema(name = "RxSendRequest")
    record SendRequest(@Pattern(regexp = "^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$") String language) {
    }

    private final PrescriptionPrintService service;
    private final RxDocumentService documents;
    private final ClinicalReadScope scopes;

    PrescriptionPrintController(PrescriptionPrintService service, RxDocumentService documents, ClinicalReadScope scopes) {
        this.service = service;
        this.documents = documents;
        this.scopes = scopes;
    }

    @org.springframework.transaction.annotation.Transactional // IDC-001: caller lookup, gate and read share one pool connection
    @com.nabd.hms.shared.phiread.PhiRead("prescription_print")
    @GetMapping("/prescriptions/{queueEntryId}/print-header")
    @PreAuthorize("hasAuthority('clinical:view')")
    public PrescriptionPrintHeaderResponse header(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId) {
        ReadScope scope = scopes.resolve(jwt);
        scopes.requireVisit(scope, queueEntryId);
        return service.header(scope.tenantId(), queueEntryId);
    }

    /** CLN-023: the signed Rx PDF (A4 or A5), stored on the first request and served byte-identical after; X-Content-SHA256 is its hash. */
    @org.springframework.transaction.annotation.Transactional
    @com.nabd.hms.shared.phiread.PhiRead("prescription_pdf")
    @GetMapping(value = "/prescriptions/{queueEntryId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAuthority('clinical:view')")
    public ResponseEntity<byte[]> pdf(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId,
                                      @RequestParam(defaultValue = "a4") String size, HttpServletRequest http) {
        if (!"a4".equals(size) && !"a5".equals(size)) {
            throw new com.nabd.hms.common.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "size must be a4 or a5.");
        }
        ReadScope scope = scopes.resolve(jwt);
        scopes.requireVisit(scope, queueEntryId);
        RxDocumentService.Pdf pdf = documents.pdf(scope.tenantId(), UUID.fromString(jwt.getSubject()), queueEntryId, "a5".equals(size), RequestMeta.clientIp(http));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore()).header("X-Content-SHA256", pdf.sha256())
                .header("Content-Disposition", "inline; filename=\"prescription-" + size + ".pdf\"").body(pdf.bytes());
    }

    /** CLN-023: sends the stored A4 PDF to the patient as an IDC-015 secure link (OTP to open); same recipient rules as POST /v1/secure-links. */
    @com.nabd.hms.shared.Idempotent
    @PostMapping("/prescriptions/{queueEntryId}/pdf/send")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('clinical:view') and hasAuthority('patients:edit')")
    public SecureDocumentLinks.Issued send(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId, @Valid @RequestBody(required = false) SendRequest req,
                                           HttpServletRequest http) {
        ReadScope scope = scopes.resolve(jwt);
        scopes.requireVisit(scope, queueEntryId);
        return documents.send(scope.tenantId(), UUID.fromString(jwt.getSubject()), queueEntryId, req == null ? null : req.language(), RequestMeta.clientIp(http));
    }

    /** CLN-023: the prescriber (CLN-005's signing rule) revokes a signed prescription once, with a reason. 409 when already revoked. */
    @com.nabd.hms.shared.Idempotent
    @PostMapping("/prescriptions/{queueEntryId}/revoke")
    @PreAuthorize("hasAnyAuthority('clinical:edit', 'clinical:approve')")
    public RevokeResponse revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId, @Valid @RequestBody RevokeRequest req, HttpServletRequest http) {
        ReadScope scope = scopes.resolve(jwt);
        scopes.requireVisit(scope, queueEntryId);
        List<String> permissions = jwt.getClaimAsStringList("permissions");
        boolean canApprove = permissions != null && permissions.contains(PrescriptionService.PERMISSION_CLINICAL_APPROVE);
        return new RevokeResponse("revoked", documents.revoke(scope.tenantId(), UUID.fromString(jwt.getSubject()), canApprove, queueEntryId, req.reason(),
                RequestMeta.clientIp(http)));
    }
}
