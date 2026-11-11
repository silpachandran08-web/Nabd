package com.nabd.hms.billing.document;

import com.nabd.hms.patient.api.SecureDocumentLinks;
import com.nabd.hms.shared.Idempotent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** FIN-035: claim packs. billing:view at the gate; the consultation summary inside also needs clinical:view (ClaimSummary). */
@RestController
@RequestMapping("/v1/billing")
class ClaimPackController {

    record GenerateRequest(@NotNull UUID visitId, @NotBlank String formatCode) {
    }

    record SendRequest(UUID recipientPatientId, @Pattern(regexp = "^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$") String language) {
    }

    record FormatList(List<ClaimPackService.FormatView> items) {
    }

    private final ClaimPackService service;

    ClaimPackController(ClaimPackService service) {
        this.service = service;
    }

    @GetMapping("/claim-pack-formats")
    @PreAuthorize("hasAuthority('billing:view')")
    FormatList formats(@RequestParam(required = false) @Pattern(regexp = "IN|SA|AE|QA") String jurisdiction) {
        return new FormatList(service.formats(jurisdiction));
    }

    @Idempotent
    @PostMapping("/claim-packs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('billing:view')")
    ClaimPackService.Generated generate(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody GenerateRequest req) {
        return service.generate(jwt, req.visitId(), req.formatCode());
    }

    @GetMapping(value = "/claim-packs/{id}/document", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAuthority('billing:view')")
    ResponseEntity<byte[]> document(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return file(service.document(jwt, id, false), MediaType.APPLICATION_PDF, "claim-pack-" + id + ".pdf", false);
    }

    @GetMapping(value = "/claim-packs/{id}/document.csv", produces = "text/csv")
    @PreAuthorize("hasAuthority('billing:view')")
    ResponseEntity<byte[]> csv(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return file(service.document(jwt, id, true), MediaType.parseMediaType("text/csv; charset=utf-8"), "claim-pack-" + id + ".csv", true);
    }

    @Idempotent
    @PostMapping("/claim-packs/{id}/send")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('billing:view')")
    SecureDocumentLinks.Issued send(@AuthenticationPrincipal Jwt jwt, HttpServletRequest http, @PathVariable UUID id, @Valid @RequestBody SendRequest req) {
        return service.send(jwt, com.nabd.hms.common.RequestMeta.clientIp(http), id, req.recipientPatientId(), req.language());
    }

    private static ResponseEntity<byte[]> file(byte[] body, MediaType type, String name, boolean attachment) {
        ContentDisposition cd = (attachment ? ContentDisposition.attachment() : ContentDisposition.inline()).filename(name).build();
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION, cd.toString()).body(body);
    }
}
