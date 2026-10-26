package com.nabd.hms.organization.bookingpolicy;

import com.nabd.hms.common.StepUpVerifier;
import com.nabd.hms.shared.Idempotent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ACC-008 / ACC-013. Permissions: the story names scheduling:view-config / scheduling:configure; the permission model has a fixed
 * module:action set, so reading is queue:view (the desk reads the rules) and every change is setup:edit (owner).
 */
@RestController
class BookingPolicyController {

    @Schema(name = "BookingPolicyPublished")
    record PublishedDto(int version, Map<String, Object> config, Instant publishedAt, UUID publishedBy) {
    }

    @Schema(name = "BookingPolicyDraft")
    record DraftDto(Map<String, Object> config, Instant updatedAt) {
    }

    @Schema(name = "BookingPolicyCurrent")
    record CurrentDto(PublishedDto published, DraftDto draft) {
    }

    @Schema(name = "BookingPolicyDraftRequest")
    record DraftRequest(@NotNull Map<String, Object> config) {
    }

    @Schema(name = "BookingPolicyDraftResponse")
    record DraftResponse(DraftDto draft) {
    }

    @Schema(name = "BookingPolicyPublishRequest")
    record PublishRequest(@Size(max = 200) String changeNote) {
    }

    @Schema(name = "BookingPolicyPublishResponse")
    record PublishResponse(int version, Instant effectiveAt) {
    }

    @Schema(name = "BookingPolicyChange")
    record ChangeDto(String pointer, Object before, Object after) {
    }

    @Schema(name = "BookingPolicyVersion")
    record VersionDto(int version, Instant publishedAt, UUID publishedBy, String publishedByName, String changeNote, List<ChangeDto> diff) {
    }

    @Schema(name = "BookingPolicyVersions")
    record VersionsResponse(List<VersionDto> items, String nextCursor) {
    }

    @Schema(name = "BookingPolicyPresetRequest")
    record PresetRequest(@NotNull String preset) {
    }

    @Schema(name = "BookingPolicyDepartmentChange")
    record DepartmentChangeDto(UUID departmentId, String departmentName, String fromTemplate, Boolean fromVitalsEnabled, boolean toVitalsEnabled) {
    }

    @Schema(name = "BookingPolicyPresetResponse")
    record PresetResponse(DraftDto draft, List<ChangeDto> changes, List<DepartmentChangeDto> departmentWorkflowChanges) {
    }

    private final BookingPolicyService service;
    private final StepUpVerifier stepUp;

    BookingPolicyController(BookingPolicyService service, StepUpVerifier stepUp) {
        this.service = service;
        this.stepUp = stepUp;
    }

    @GetMapping("/v1/booking-policy")
    @PreAuthorize("hasAuthority('queue:view')")
    CurrentDto get() {
        BookingPolicyService.Current c = service.current();
        return new CurrentDto(c.published() == null ? null : new PublishedDto(c.published().version(), c.published().config(),
                c.published().publishedAt(), c.published().publishedBy()), draft(c.draft()));
    }

    @PutMapping("/v1/booking-policy/draft")
    @PreAuthorize("hasAuthority('setup:edit')")
    DraftResponse saveDraft(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DraftRequest req) {
        return new DraftResponse(draft(service.saveDraft(UUID.fromString(jwt.getSubject()), req.config())));
    }

    @Idempotent
    @PostMapping("/v1/booking-policy/draft/publish")
    @PreAuthorize("hasAuthority('setup:edit')")
    PublishResponse publish(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) @Valid PublishRequest req,
                            @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken) {
        UUID staff = UUID.fromString(jwt.getSubject());
        stepUp.require(stepUpToken, staff);
        Map<String, Object> r = service.publish(staff, req == null ? null : req.changeNote());
        return new PublishResponse((Integer) r.get("version"), (Instant) r.get("effectiveAt"));
    }

    @PostMapping("/v1/booking-policy/draft/apply-preset")
    @PreAuthorize("hasAuthority('setup:edit')")
    PresetResponse applyPreset(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PresetRequest req) {
        BookingPolicyService.Applied a = service.applyPreset(UUID.fromString(jwt.getSubject()), req.preset());
        return new PresetResponse(draft(a.draft()), a.changes().stream().map(c -> new ChangeDto(c.pointer(), c.before(), c.after())).toList(),
                a.departmentWorkflowChanges().stream().map(c -> new DepartmentChangeDto(c.departmentId(), c.departmentName(), c.fromTemplate(),
                        c.fromVitalsEnabled(), c.toVitalsEnabled())).toList());
    }

    @GetMapping("/v1/booking-policy/versions")
    @PreAuthorize("hasAuthority('queue:view')")
    VersionsResponse versions(@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        BookingPolicyService.Page p = service.versions(cursor, Math.max(1, Math.min(limit, 100)));
        return new VersionsResponse(p.items().stream().map(v -> new VersionDto(v.version(), v.publishedAt(), v.publishedBy(), v.publishedByName(),
                v.changeNote(), v.diff().stream().map(c -> new ChangeDto(c.pointer(), c.before(), c.after())).toList())).toList(), p.nextCursor());
    }

    private static DraftDto draft(BookingPolicyService.Draft d) {
        return d == null ? null : new DraftDto(d.config(), d.updatedAt());
    }
}
