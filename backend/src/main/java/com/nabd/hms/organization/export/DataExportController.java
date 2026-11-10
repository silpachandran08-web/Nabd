package com.nabd.hms.organization.export;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.common.StepUpVerifier;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.common.WhatsAppOtpSender;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ADM-015: start and follow a full export (owner: setup:export, plus a step-up asked for with target "data-export", used once), and
 * the public download link (the token is the credential; an OTP to the owner's mobile is needed for every download). A caller
 * without setup:export gets 403 and the attempt is audited (handler below).
 */
@RestController
class DataExportController {

    private static final Logger log = LoggerFactory.getLogger(DataExportController.class);

    @Schema(name = "DataExportRequest")
    record StartBody(@Pattern(regexp = "full_tenant") String scope, @Size(max = 20) List<@Pattern(regexp = "[a-z_]{2,20}") String> datasets,
                     @NotBlank @Pattern(regexp = "xlsx|csv|fhir_ndjson|pdf_per_patient") String format, @Size(max = 20) List<UUID> branchIds) {
    }

    @Schema(name = "DataExportDownloadRequest")
    record DownloadBody(@NotBlank @Pattern(regexp = "^[0-9]{6}$") String otp) {
    }

    private final DataExportService service;
    private final StepUpVerifier stepUp;
    private final TenantScope tenantScope;
    private final JdbcTemplate jdbc;
    private final WhatsAppOtpSender sender;

    DataExportController(DataExportService service, StepUpVerifier stepUp, TenantScope tenantScope, JdbcTemplate jdbc, WhatsAppOtpSender sender) {
        this.service = service;
        this.stepUp = stepUp;
        this.tenantScope = tenantScope;
        this.jdbc = jdbc;
        this.sender = sender;
    }

    @PostMapping("/v1/setup/export-jobs")
    @PreAuthorize("hasAuthority('setup:export')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    DataExportService.Started start(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StartBody b, HttpServletRequest http,
                                    @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken) {
        UUID staff = UUID.fromString(jwt.getSubject());
        StepUpVerifier.StepUp su = stepUp.requireFor(stepUpToken, staff, DataExportService.STEP_UP_TARGET);
        return service.start(staff, su.assertionId(), new DataExportService.StartRequest(b.scope(), b.datasets(), b.format(), b.branchIds()), RequestMeta.clientIp(http));
    }

    @GetMapping("/v1/setup/export-jobs/{id}")
    @PreAuthorize("hasAuthority('setup:export')")
    DataExportService.Job get(@PathVariable UUID id) {
        return service.get(id);
    }

    /** 403 like the security chain's, after an audit row naming who tried (ids only). */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> denied(HttpServletRequest http) {
        if (SecurityContextHolder.getContext().getAuthentication() != null && SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof Jwt jwt
                && jwt.getClaimAsString("tenantId") != null) {
            try {
                tenantScope.run(UUID.fromString(jwt.getClaimAsString("tenantId")), () -> service.denied(UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http)));
            } catch (RuntimeException e) {
                log.warn("export denial not audited ({})", e.getClass().getSimpleName());
            }
        }
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "You do not have permission to do this.");
        p.setType(URI.create("https://api.nabd.health/problems/forbidden"));
        p.setTitle("Forbidden");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
    }

    // ---------------------------------------------------------------- public link

    @GetMapping("/v1/public/exports/{token}")
    ResponseEntity<DataExportService.LinkView> view(@PathVariable String token) {
        String hash = OpaqueTokens.sha256Hex(token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tenantScope.run(tenantOf(hash), () -> service.view(hash)));
    }

    @PostMapping("/v1/public/exports/{token}/otp")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Boolean> otp(@PathVariable String token) {
        String hash = OpaqueTokens.sha256Hex(token);
        DataExportService.Otp otp = tenantScope.run(tenantOf(hash), () -> service.issueOtp(hash));
        try { // after the commit, with no transaction open
            sender.send(otp.phone(), otp.code());
        } catch (RuntimeException e) {
            log.warn("export OTP delivery failed");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "otp-send-failed", "Code not sent", "We could not send the code. Try again in a moment.");
        }
        return Map.of("sent", true);
    }

    @PostMapping("/v1/public/exports/{token}/download")
    ResponseEntity<byte[]> download(@PathVariable String token, @Valid @RequestBody DownloadBody b, HttpServletRequest http) {
        String hash = OpaqueTokens.sha256Hex(token);
        DataExportService.Download d = tenantScope.run(tenantOf(hash), () -> service.download(hash, b.otp(), RequestMeta.clientIp(http)));
        return switch (d.outcome()) {
            case OK -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("application/zip"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"nabd-export.zip\"").body(d.archive());
            case EXPIRED -> throw DataExportService.gone();
            case OTP_INVALID -> throw new ApiException(HttpStatus.UNAUTHORIZED, "otp-invalid", "Code not accepted", "That code is wrong or has expired. Ask for a new one.", Map.of("code", "otp-invalid"));
        };
    }

    /** The token names no clinic by itself: the definer function (V3001) returns the link's tenant and nothing else. */
    private UUID tenantOf(String hash) {
        UUID t = jdbc.queryForObject("SELECT data_export_link_tenant(?)", UUID.class, hash);
        if (t == null) {
            throw DataExportService.gone();
        }
        return t;
    }
}
