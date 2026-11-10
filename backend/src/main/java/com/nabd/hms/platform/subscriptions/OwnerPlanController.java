package com.nabd.hms.platform.subscriptions;

import com.nabd.hms.common.RequestMeta;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * ADM-037: the owner's plan change and Nabd invoices (GET /v1/setup/subscription itself stays in SetupController). Reads need
 * setup:view like the plan page; a change needs setup:edit, the built-in Owner role and a step-up bound to the target plan
 * (no separate plan:* permission exists in the RBAC matrix; see the ADM-037 report).
 */
@RestController
@RequestMapping("/v1/setup/subscription")
class OwnerPlanController {

    record ChangeRequest(@NotBlank @Size(max = 100) String targetPlanCode,
                         @NotBlank @Pattern(regexp = "now|renewal") String effective,
                         @Size(max = 200) String confirmClinicName) {
    }

    private final OwnerPlanService service;

    OwnerPlanController(OwnerPlanService service) {
        this.service = service;
    }

    @GetMapping("/change-preview")
    @PreAuthorize("hasAuthority('setup:view')")
    OwnerPlanService.Preview preview(@RequestParam String targetPlanCode, @RequestParam(defaultValue = "now") String effective) {
        return service.preview(targetPlanCode, effective);
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping("/change")
    @PreAuthorize("hasAuthority('setup:edit')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    OwnerPlanService.ChangeResult change(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangeRequest req, HttpServletRequest http,
                                         @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken) {
        return service.change(UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http), req.targetPlanCode(), req.effective(),
                req.confirmClinicName(), stepUpToken);
    }

    @GetMapping("/invoices")
    @PreAuthorize("hasAuthority('setup:view')")
    OwnerPlanService.InvoicePage invoices(@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        return service.invoices(limit, cursor);
    }

    @GetMapping("/invoices/{id}")
    @PreAuthorize("hasAuthority('setup:view')")
    OwnerPlanService.InvoiceItem invoice(@PathVariable UUID id) {
        return service.invoice(id);
    }
}
