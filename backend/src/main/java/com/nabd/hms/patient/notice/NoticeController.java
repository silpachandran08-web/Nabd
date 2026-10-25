package com.nabd.hms.patient.notice;

import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.common.StepUpVerifier;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** IDC-003: the clinic's notice versions (settings), and the desk's re-acknowledgement of a newer notice. */
@RestController
class NoticeController {

    @Schema(name = "PrivacyNoticeVersion")
    record VersionItem(String version, String language, OffsetDateTime effectiveFrom, String mandatoryHash, String templateKey,
                       boolean legalReviewed, boolean current, String bodyMd, List<String> lockedClauses) {
    }

    @Schema(name = "PrivacyNoticeVersions")
    record VersionsResponse(List<VersionItem> versions) {
    }

    @Schema(name = "PrivacyNoticePublishRequest")
    record PublishRequest(@NotBlank @Pattern(regexp = "^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$") String language,
                          @NotBlank @Size(max = 50000) String bodyMd,
                          @NotBlank @Size(max = 60) String basedOnTemplate,
                          @Schema(description = "The version being edited; a different current version gives 409 version-conflict") String baseVersion) {
    }

    @Schema(name = "PrivacyNoticePublished")
    record PublishResponse(String version) {
    }

    @Schema(name = "PrivacyNoticeStatus")
    record StatusResponse(boolean needsReAcknowledgement, String acceptedVersion, String currentVersion, String language) {
    }

    private final NoticeService service;
    private final StepUpVerifier stepUp;

    NoticeController(NoticeService service, StepUpVerifier stepUp) {
        this.service = service;
        this.stepUp = stepUp;
    }

    @GetMapping("/v1/settings/privacy/privacy-notice")
    @PreAuthorize("hasAuthority('setup:view')")
    VersionsResponse versions() {
        var all = service.versions();
        return new VersionsResponse(all.stream().map(n -> {
            // rows come newest first per language, so the first of a language is the current one
            boolean current = all.stream().filter(x -> x.language().equals(n.language())).findFirst().orElseThrow() == n;
            return new VersionItem(n.version(), n.language(), n.effectiveFrom(), n.mandatoryHash(), n.templateKey(), n.legalReviewed(),
                    current, current ? n.bodyMd() : null, current ? service.lockedClauses(n.templateKey(), n.language()) : null);
        }).toList());
    }

    @PutMapping("/v1/settings/privacy/privacy-notice")
    @PreAuthorize("hasAuthority('setup:edit')")
    PublishResponse publish(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PublishRequest req, HttpServletRequest http,
                            @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken) {
        UUID staff = UUID.fromString(jwt.getSubject());
        stepUp.require(stepUpToken, staff);
        return new PublishResponse(service.publish(staff, RequestMeta.clientIp(http), req.language(), req.bodyMd(), req.basedOnTemplate(), req.baseVersion()));
    }

    @GetMapping("/v1/patients/{patientId}/privacy-notice")
    @PreAuthorize("hasAuthority('patients:view')")
    StatusResponse status(@PathVariable UUID patientId) {
        return toStatus(service.reack(patientId));
    }

    @PostMapping("/v1/patients/{patientId}/privacy-notice/acknowledge")
    @PreAuthorize("hasAuthority('patients:edit')")
    StatusResponse acknowledge(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId, HttpServletRequest http) {
        return toStatus(service.acknowledge(patientId, UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http)));
    }

    private static StatusResponse toStatus(NoticeService.Reack r) {
        return new StatusResponse(r.needsReAcknowledgement(), r.acceptedVersion(), r.currentVersion(), r.language());
    }
}
