package com.nabd.hms.clinical.derm;

import com.nabd.hms.clinical.ClinicalReadScope;
import com.nabd.hms.common.RequestMeta;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * CLN-047: dermatology photo consent and timeline. Writes need specialty_derm:edit and the clinic's plan (SpecialtyWriteGuard:
 * 403 ENTITLEMENT_EXPIRED), except the withdrawal, which a patient can always ask for. Every call passes the IDC-001 read gate.
 */
@RestController
@RequestMapping("/v1/specialty/derm/patients/{patientId}")
public class DermPhotoController {

    @Schema(name = "DermPhotoConsentRequest")
    public record ConsentRequest(@NotNull @Pattern(regexp = "record_only|record_and_teaching") String scope,
                                 @NotNull @Pattern(regexp = "patient|guardian") String signerKind,
                                 @Size(max = 200000) @Schema(description = "SVG path strokes in a 600 x 200 box, as for procedure consent (CLN-014)") String signatureSvg) {
    }

    @Schema(name = "DermPhotoConsentCreated")
    public record ConsentCreated(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID consentId) {
    }

    @Schema(name = "DermPhotoConsentWithdrawn")
    public record ConsentWithdrawn(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Photos now hidden (kept, never deleted)") int photosHidden) {
    }

    /** Body site: region from the body map (CLN-049), side, optional map point. */
    @Schema(name = "DermPhotoSite")
    public record Site(@NotNull @Pattern(regexp = "^[a-z][a-z0-9_]{1,39}$") String region,
                       @NotNull @Pattern(regexp = "left|right|midline|none") String side,
                       @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$") String mapPointId) {
    }

    @Schema(name = "DermPhotoCreateRequest")
    public record PhotoRequest(@NotNull UUID uploadId, @NotNull UUID queueEntryId, @NotNull @Valid Site site, @Size(max = 500) String note) {
    }

    @Schema(name = "DermPhotoCreated")
    public record PhotoCreated(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID photoId) {
    }

    @Schema(name = "DermPhotoConsent")
    public record Consent(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID consentId,
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"record_only", "record_and_teaching"}) String scope,
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String signerKind,
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant recordedAt) {
    }

    @Schema(name = "DermPhoto")
    public record Photo(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant takenAt,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID queueEntryId,
                        @Schema(nullable = true) LocalDate visitDate,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Site site,
                        @Schema(nullable = true) String note,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Signed single-object GET URL, valid 5 minutes") String url,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant urlExpiresAt,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Scope of the consent the photo was taken under",
                                allowableValues = {"record_only", "record_and_teaching"}) String consentBadge) {
    }

    @Schema(name = "DermPhotoSiteGroup")
    public record SitePhotos(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Site site,
                             @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Oldest first") List<Photo> photos) {
    }

    @Schema(name = "DermPhotoTimeline")
    public record Timeline(@Schema(nullable = true, description = "The active photo consent; null = none (capture it before any photo)") Consent consent,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Photos hidden by a withdrawn consent (retained)") int hiddenCount,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<SitePhotos> sites) {
    }

    private final DermPhotoService service;
    private final ClinicalReadScope scopes;

    DermPhotoController(DermPhotoService service, ClinicalReadScope scopes) {
        this.service = service;
        this.scopes = scopes;
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping("/photo-consent")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('specialty_derm:edit')")
    public ConsentCreated recordConsent(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId, @Valid @RequestBody ConsentRequest req,
                                        HttpServletRequest http) {
        return new ConsentCreated(service.recordConsent(scopes.resolve(jwt), patientId, req.scope(), req.signerKind(), req.signatureSvg(),
                RequestMeta.clientIp(http)));
    }

    @PostMapping("/photo-consent/withdraw")
    @PreAuthorize("hasAuthority('specialty_derm:edit')")
    public ConsentWithdrawn withdrawConsent(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId, HttpServletRequest http) {
        return new ConsentWithdrawn(service.withdrawConsent(scopes.resolve(jwt), patientId, RequestMeta.clientIp(http)));
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping("/photos")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('specialty_derm:edit')")
    public PhotoCreated addPhoto(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId, @Valid @RequestBody PhotoRequest req,
                                 HttpServletRequest http) {
        return new PhotoCreated(service.addPhoto(scopes.resolve(jwt), patientId, req.uploadId(), req.queueEntryId(), req.site(), req.note(),
                RequestMeta.clientIp(http)));
    }

    @PhiRead("derm_photos")
    @GetMapping("/photos")
    @PreAuthorize("hasAuthority('specialty_derm:view')")
    public Timeline timeline(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId,
                             @RequestParam(required = false) @Pattern(regexp = "^[a-z][a-z0-9_]{1,39}$") String site) {
        return service.timeline(scopes.resolve(jwt), patientId, site);
    }
}
