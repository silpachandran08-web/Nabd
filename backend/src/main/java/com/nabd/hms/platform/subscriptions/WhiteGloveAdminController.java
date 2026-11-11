package com.nabd.hms.platform.subscriptions;

import com.nabd.hms.common.RequestMeta;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** ADM-006: the onboarding team's white-glove queue (onboarding:view) and its status changes (onboarding:edit). The queue holds
 * engagement data and the clinic name only; entering the clinic needs an owner-approved support-access grant on the ticket. */
@RestController
@RequestMapping("/v1/platform/white-glove")
class WhiteGloveAdminController {

    record TransitionRequest(@NotBlank @Pattern(regexp = "scheduled|in_progress|done|cancelled") String toStatus, Instant slotAt,
                             @Size(max = 9) List<String> coveredSteps) {
    }

    private final WhiteGloveService service;

    WhiteGloveAdminController(WhiteGloveService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('onboarding:view')")
    List<WhiteGloveService.QueueItem> queue(@RequestParam(defaultValue = "false") boolean includeClosed) {
        return service.queue(includeClosed);
    }

    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAuthority('onboarding:edit')")
    WhiteGloveService.Transitioned transition(@PathVariable UUID id, @Valid @RequestBody TransitionRequest req, @AuthenticationPrincipal Jwt jwt,
                                              HttpServletRequest http) {
        return service.transition(id, req.toStatus(), req.slotAt(), req.coveredSteps(), UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http));
    }
}
