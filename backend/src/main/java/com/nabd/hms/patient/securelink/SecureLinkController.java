package com.nabd.hms.patient.securelink;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** IDC-015: staff issue a secure link for a document. No `documents:share` permission exists in the model; patients:edit (doctor, reception) is used. */
@RestController
@RequestMapping("/v1/secure-links")
class SecureLinkController {

    @Schema(name = "SecureLinkIssueRequest")
    record IssueRequest(@NotBlank @Pattern(regexp = "^(rx|invoice|report|document)$") String documentType, @NotNull UUID documentId,
                        @NotNull UUID recipientRef, @Min(1) @Max(30) Integer ttlDays, Boolean sendMessage,
                        @Pattern(regexp = "^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$") String language) {
    }

    @Schema(name = "SecureLinkIssueResponse")
    record IssueResponse(UUID linkId, Instant expiresAt) {
    }

    private final SecureLinkService service;

    SecureLinkController(SecureLinkService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('patients:edit')")
    IssueResponse issue(@AuthenticationPrincipal Jwt jwt, HttpServletRequest http, @Valid @RequestBody IssueRequest req) {
        SecureLinkService.Issued i = service.issue(UUID.fromString(jwt.getSubject()), com.nabd.hms.common.RequestMeta.clientIp(http),
                new SecureLinkService.IssueRequest(req.documentType(), req.documentId(), req.recipientRef(), req.ttlDays(), req.sendMessage(), req.language()));
        return new IssueResponse(i.linkId(), i.expiresAt());
    }
}
