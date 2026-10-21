package com.nabd.hms.messaging;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** COM-014: the editor's dry run, and the platform operator's audited override for a false positive. */
@RestController
class TemplateLintController {

    @Schema(name = "TemplateLintRequest")
    public record LintRequest(String body, String language, List<TemplateLint.Slot> slots) {
    }

    @Schema(name = "TemplateLintResponse")
    public record LintResponse(boolean ok, List<TemplateLint.Finding> findings) {
    }

    @Schema(name = "TemplateOverrideRequest")
    public record OverrideRequest(@NotBlank @Size(max = 100) String name, @NotBlank String language, @NotBlank String category,
                                  String body, List<String> examples, List<TemplateLint.Slot> slots,
                                  @NotBlank @Size(min = 10, max = 500) String reason) {
    }

    private final TemplateLint lint;
    private final WhatsAppTemplateService service;

    TemplateLintController(TemplateLint lint, WhatsAppTemplateService service) {
        this.lint = lint;
        this.service = service;
    }

    @PostMapping("/v1/setup/whatsapp-templates:lint")
    @PreAuthorize("hasAuthority('setup:edit')")
    LintResponse dryRun(@RequestBody LintRequest req) {
        List<TemplateLint.Finding> findings = lint.lint(req.body(), req.slots());
        return new LintResponse(findings.isEmpty(), findings);
    }

    @PostMapping("/v1/platform/tenants/{tenantId}/whatsapp-templates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('messaging_operations:override')")
    WhatsAppTemplateService.TemplateResponse createWithOverride(@PathVariable UUID tenantId, @RequestBody @jakarta.validation.Valid OverrideRequest req,
                                                                @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return service.create(new WhatsAppTemplateService.TemplateRequest(req.name(), req.language(), req.category(), req.body(),
                req.examples(), req.slots()), new WhatsAppTemplateService.PhiOverride(tenantId, UUID.fromString(jwt.getSubject()),
                com.nabd.hms.common.RequestMeta.clientIp(http), req.reason()));
    }
}
