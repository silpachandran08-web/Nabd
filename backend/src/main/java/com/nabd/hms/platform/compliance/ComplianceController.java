package com.nabd.hms.platform.compliance;

import com.nabd.hms.common.RequestMeta;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * PLT-054: the operator console's side of the security training and the regulatory watch. An operator records and reads
 * their own training pass with any operator session; the team list, the watch log (read and write) and the quarterly
 * summary sit behind audit_compliance:view (super_admin, compliance_dpo), like the audit-log export: PlatformPermissions
 * has no separate write grant for the two roles that already hold the surface.
 */
@RestController
@RequestMapping("/v1/platform")
public class ComplianceController {

    @Schema(name = "OperatorTrainingCompletionRequest")
    record CompletionRequest(@NotBlank String moduleId, @NotNull Integer score) {
    }

    private final OperatorTrainingService training;
    private final RegulatoryWatchService watch;

    ComplianceController(OperatorTrainingService training, RegulatoryWatchService watch) {
        this.training = training;
        this.watch = watch;
    }

    /** 201 for a new pass, 200 when today's pass was already recorded (idempotent). */
    @PostMapping("/training/completions")
    ResponseEntity<OperatorTrainingService.Completion> complete(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CompletionRequest req, HttpServletRequest http) {
        OperatorTrainingService.Completion c = training.record(UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http), req.moduleId(), req.score());
        return ResponseEntity.status(c.created() ? HttpStatus.CREATED : HttpStatus.OK).body(c);
    }

    @GetMapping("/training/completions/me")
    OperatorTrainingService.Status mine(@AuthenticationPrincipal Jwt jwt) {
        return training.mine(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/training/completions")
    @PreAuthorize("hasAuthority('audit_compliance:view')")
    OperatorTrainingService.Roster roster() {
        return training.roster();
    }

    @GetMapping("/regulatory-watch")
    @PreAuthorize("hasAuthority('audit_compliance:view')")
    RegulatoryWatchService.Log log(@RequestParam(required = false) Integer year, @RequestParam(required = false) Integer quarter) {
        return watch.list(year, quarter);
    }

    @PostMapping("/regulatory-watch")
    @PreAuthorize("hasAuthority('audit_compliance:view')")
    @ResponseStatus(HttpStatus.CREATED)
    RegulatoryWatchService.Entry record(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RegulatoryWatchService.EntryRequest req, HttpServletRequest http) {
        return watch.record(UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http), req);
    }

    @GetMapping("/regulatory-watch/summary")
    @PreAuthorize("hasAuthority('audit_compliance:view')")
    ResponseEntity<String> summary(@RequestParam Integer year, @RequestParam Integer quarter, @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        String md = watch.summary(UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http), year, quarter);
        return ResponseEntity.ok().contentType(MediaType.valueOf("text/markdown;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"regulatory-watch-" + year + "-Q" + quarter + ".md\"")
                .body(md);
    }
}
