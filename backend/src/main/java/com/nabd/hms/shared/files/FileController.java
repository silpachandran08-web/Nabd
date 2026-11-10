package com.nabd.hms.shared.files;

import com.nabd.hms.common.ApiException;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** PLT-029: reserve a slot, complete the upload, read status. Bytes go straight to the signed URL, never through these calls. */
@RestController
@RequestMapping("/v1/files")
class FileController {

    @Schema(name = "FileUploadInitRequest")
    record InitRequest(@NotBlank String purpose, @NotBlank String contentType, @NotNull Long sizeBytes) {
    }

    @Schema(name = "FileUploadInitResponse")
    record InitResponse(UUID uploadId, String putUrl, long maxBytes) {
    }

    @Schema(name = "FileUploadCompleteRequest")
    record CompleteRequest(@Pattern(regexp = "^[0-9a-fA-F]{64}$") String sha256) {
    }

    @Schema(name = "FileUploadCompleteResponse")
    record CompleteResponse(UUID fileId, String status) {
    }

    @Schema(name = "FileStatusResponse")
    record StatusResponse(UUID fileId, String status, String contentType, long sizeBytes, Instant createdAt) {
    }

    private final FileService service;

    FileController(FileService service) {
        this.service = service;
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping("/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('clinical:create')")
    InitResponse init(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody InitRequest req) {
        FileService.Slot s = service.init(UUID.fromString(jwt.getSubject()), req.purpose(), req.contentType(), req.sizeBytes());
        return new InitResponse(s.uploadId(), s.putUrl(), s.maxBytes());
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping("/uploads/{id}/complete")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('clinical:create')")
    CompleteResponse complete(@PathVariable UUID id, @Valid @RequestBody(required = false) CompleteRequest req) {
        return new CompleteResponse(service.complete(id, req == null ? null : req.sha256()), "pending_scan");
    }

    /** Status and type only (no patient data), so no PHI read gate. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('clinical:view')")
    StatusResponse get(@PathVariable UUID id) {
        com.nabd.hms.shared.FileStore.FileInfo f = service.info(id).orElseThrow(ApiException::notFound);
        return new StatusResponse(f.fileId(), f.status(), f.contentType(), f.sizeBytes(), f.createdAt());
    }
}
