package com.nabd.hms.clinical;

import com.nabd.hms.common.ApiException;

import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.shared.FileStore;
import com.nabd.hms.shared.phiread.PhiRead;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** PLT-030: visit attachments and the short-lived signed download URL. Every call passes the IDC-001 read gate (withdrawn consent is 404). */
@RestController
class AttachmentController {

    @Schema(name = "AttachmentCreateRequest")
    record CreateRequest(@NotNull UUID fileId, @NotNull @Pattern(regexp = "outside_report|photo|xray") String kind, @Size(max = 500) String note) {
    }

    @Schema(name = "AttachmentCreateResponse")
    record Created(UUID attachmentId) {
    }

    @Schema(name = "VisitAttachmentItem")
    record Item(UUID attachmentId, UUID patientId, UUID fileId, String kind, String note, Instant createdAt, String contentType) {
    }

    @Schema(name = "VisitAttachmentList")
    record ItemList(List<Item> items) {
    }

    @Schema(name = "FileDownloadUrlResponse")
    record DownloadUrl(String url, Instant expiresAt, UUID patientId) {
    }

    private final AttachmentService service;
    private final ClinicalReadScope scopes;
    private final FileStore files;

    AttachmentController(AttachmentService service, ClinicalReadScope scopes, FileStore files) {
        this.service = service;
        this.scopes = scopes;
        this.files = files;
    }

    @Transactional
    @com.nabd.hms.shared.Idempotent
    @PostMapping("/v1/clinical/visits/{visitId}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('clinical:edit')")
    Created attach(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID visitId, @Valid @RequestBody CreateRequest req, HttpServletRequest http) {
        scopes.requireVisit(scopes.resolve(jwt), visitId);
        return new Created(service.attach(visitId, req.fileId(), req.kind(), req.note(), UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http)));
    }

    @Transactional
    @PhiRead("attachment_list")
    @GetMapping("/v1/clinical/visits/{visitId}/attachments")
    @PreAuthorize("hasAuthority('clinical:view')")
    ItemList list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID visitId) {
        scopes.requireVisit(scopes.resolve(jwt), visitId);
        return new ItemList(service.list(visitId));
    }

    /** A POST because it issues a credential; it is a PHI read all the same, so it writes one phi_read_log row per issuance. */
    @Transactional
    @PhiRead("attachment")
    @PostMapping("/v1/files/{id}/download-url")
    @PreAuthorize("hasAuthority('clinical:view')")
    DownloadUrl downloadUrl(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        FileStore.FileInfo f = files.info(id).filter(i -> i.patientId() != null).orElseThrow(ApiException::notFound);
        scopes.requirePatient(scopes.resolve(jwt), f.patientId());
        FileStore.SignedUrl u = files.downloadUrl(id);
        return new DownloadUrl(u.url(), u.expiresAt(), f.patientId());
    }
}
