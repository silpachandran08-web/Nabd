package com.nabd.hms.patient.consent;

import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.shared.Idempotent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** IDC-004/007: itemised consent captured at the desk (DESK) or assisted and witnessed (ASSISTED). */
@RestController
class ConsentCaptureController {

    @Schema(name = "ConsentDecision")
    record Decision(@NotNull @Pattern(regexp = "processing|messaging|marketing") String purpose, @NotNull Boolean granted) {
    }

    @Schema(name = "ConsentCaptureRequest")
    record CaptureRequest(@NotEmpty @Size(max = 3) List<@Valid Decision> decisions,
                          @NotBlank @Size(max = 40) String noticeVersion,
                          @NotBlank @Pattern(regexp = "^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$") String language,
                          @NotNull @Pattern(regexp = "DESK|ASSISTED") String channel,
                          @Schema(description = "Required for a patient under 18: the linked guardian") UUID guardianPatientId,
                          @Schema(description = "ASSISTED: staff who witnessed; the capturing receptionist always counts as a witness") @Size(max = 3) List<UUID> witnessStaffIds,
                          @Schema(description = "Processing refused: record the emergency / treatment-only basis") Boolean emergencyTreatmentBasis) {
    }

    @Schema(name = "ConsentCaptured")
    record Captured(UUID id, String purpose, boolean granted, String noticeVersion, OffsetDateTime capturedAt) {
    }

    @Schema(name = "ConsentCaptureResponse")
    record CaptureResponse(List<Captured> consents) {
    }

    @Schema(name = "ConsentPurposeState")
    record PurposeState(boolean granted, String noticeVersion, String language, String channel, OffsetDateTime capturedAt) {
    }

    @Schema(name = "PatientConsentState")
    record StateResponse(Map<String, PurposeState> purposes, boolean needsReack, boolean processable, boolean needsConsent,
                         boolean emergencyTreatmentBasis, @Schema(description = "language -> current notice version, for the capture request") Map<String, String> notices) {
    }

    private final ConsentCaptureService service;

    ConsentCaptureController(ConsentCaptureService service) {
        this.service = service;
    }

    @Idempotent
    @PostMapping("/v1/patients/{id}/consents")
    @PreAuthorize("hasAuthority('patients:edit')")
    @ResponseStatus(HttpStatus.CREATED)
    CaptureResponse capture(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody CaptureRequest req, HttpServletRequest http) {
        return service.capture(UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http), id, req);
    }

    @GetMapping("/v1/patients/{id}/consents")
    @PreAuthorize("hasAuthority('patients:view')")
    StateResponse state(@PathVariable UUID id) {
        return service.state(id);
    }
}
