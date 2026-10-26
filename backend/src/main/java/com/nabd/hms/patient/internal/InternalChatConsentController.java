package com.nabd.hms.patient.internal;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.iam.api.InternalCaller;
import com.nabd.hms.iam.api.ServiceIdentity;
import com.nabd.hms.patient.api.ConsentRegistry;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * IDC-005: the Patient Access Edge relays what a first-time WhatsApp booker tapped. The clinic comes from the path (the
 * InternalCallerFilter checks it against the service token's tenants), not from the body. No name, DOB or phone number is accepted.
 */
@RestController
class InternalChatConsentController {

    @Schema(name = "ChatConsentDecision")
    record DecisionDto(@NotBlank @Size(max = 20) String purpose, @NotNull Boolean granted) {
    }

    @Schema(name = "ChatConsentEvidence")
    record EvidenceDto(@NotBlank @Pattern(regexp = "^[A-Za-z0-9=_.:-]{1,128}$") String wamid) {
    }

    @Schema(name = "ChatConsentRequest")
    record Request(@NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String phoneHash,
                   @NotEmpty @Size(max = 3) List<@Valid DecisionDto> decisions,
                   @NotBlank @Size(max = 40) String noticeVersion,
                   @NotBlank @Pattern(regexp = "^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$") String language,
                   @NotNull @Valid EvidenceDto evidence) {
    }

    @Schema(name = "ChatConsentResponse")
    record Response(List<UUID> consentIds) {
    }

    @Schema(name = "ChatNoticeCurrent")
    record NoticeCurrent(String version, String language, String path) {
    }

    private final TenantScope tenantScope;
    private final ChatConsentService service;
    private final ConsentRegistry registry;
    private final JdbcTemplate jdbc;

    InternalChatConsentController(TenantScope tenantScope, ChatConsentService service, ConsentRegistry registry, JdbcTemplate jdbc) {
        this.tenantScope = tenantScope;
        this.service = service;
        this.registry = registry;
        this.jdbc = jdbc;
    }

    @PostMapping("/internal/v1/tenants/{tid}/patients/consents")
    @ResponseStatus(HttpStatus.CREATED)
    Response capture(@PathVariable UUID tid, @Valid @RequestBody Request req) {
        ServiceIdentity edge = allowed(tid);
        return tenantScope.run(tid, () -> new Response(service.capture(req.phoneHash(),
                req.decisions().stream().map(d -> new ChatConsentService.Decision(d.purpose(), d.granted())).toList(),
                req.noticeVersion(), req.language(), req.evidence().wamid(), edge.serviceId())));
    }

    /** The notice the consent step must show: its version (echoed back on capture) and the public page path. */
    @GetMapping("/internal/v1/tenants/{tid}/privacy-notice/current")
    NoticeCurrent currentNotice(@PathVariable UUID tid, @RequestParam(defaultValue = "en") @Pattern(regexp = "^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$") String language) {
        allowed(tid);
        return tenantScope.run(tid, () -> {
            ConsentRegistry.NoticeRef n = registry.currentNotice(language)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "notice-not-found", "Notice not found", "The clinic has no notice in that language."));
            String slug = jdbc.queryForObject("SELECT slug::text FROM tenants WHERE id = ?", String.class, tid);
            return new NoticeCurrent(n.version(), n.language(), "/v1/public/notices/" + slug + "/" + n.version() + "?lang=" + n.language());
        });
    }

    private static ServiceIdentity allowed(UUID tid) {
        ServiceIdentity edge = InternalCaller.current();
        if (!edge.mayAct(tid)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "This service identity may not act for that clinic.");
        }
        return edge;
    }
}
