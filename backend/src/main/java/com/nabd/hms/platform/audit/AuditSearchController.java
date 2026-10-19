package com.nabd.hms.platform.audit;

import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.platform.audit.dto.AuditLogPage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

import static com.nabd.hms.platform.audit.AuditSearchModels.AuditFilter;

/** Gated on audit_compliance:view — super_admin/compliance_dpo (PLT-056 removed sre). The CSV export
 * (PLT-028) sits behind the same authority: PlatformPermissions has no separate export grant, and the export
 * shows nothing the view does not; it is audited instead (see AuditSearchService.exportCsv). */
@RestController
@RequestMapping("/v1/platform/audit-log")
@PreAuthorize("hasAuthority('audit_compliance:view')")
public class AuditSearchController {

    private final AuditSearchService service;

    AuditSearchController(AuditSearchService service) {
        this.service = service;
    }

    @GetMapping
    public AuditLogPage search(@RequestParam(required = false) UUID tenantId,
                                @RequestParam(required = false) String action,
                                @RequestParam(required = false) String entityType,
                                @RequestParam(required = false) Instant createdAfter,
                                @RequestParam(required = false) Instant createdBefore,
                                @RequestParam(required = false) UUID actorId,
                                @RequestParam(required = false) String actorType,
                                @RequestParam(required = false) String actorRole,
                                @RequestParam(required = false) String actorName,
                                @RequestParam(required = false) Integer limit,
                                @RequestParam(required = false) String cursor,
                                @RequestParam(defaultValue = "false") boolean includeValues,
                                @RequestParam(required = false) String reason,
                                @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return service.search(new AuditFilter(tenantId, action, entityType, createdAfter, createdBefore,
                actorId, actorType, actorRole, actorName), limit, cursor,
                new AuditSearchService.ValueAccess(includeValues, reason), UUID.fromString(jwt.getSubject()),
                RequestMeta.clientIp(http));
    }

    @GetMapping("/export")
    public ResponseEntity<String> export(@RequestParam(required = false) UUID tenantId,
                                         @RequestParam(required = false) String action,
                                         @RequestParam(required = false) String entityType,
                                         @RequestParam(required = false) Instant createdAfter,
                                         @RequestParam(required = false) Instant createdBefore,
                                         @RequestParam(required = false) UUID actorId,
                                         @RequestParam(required = false) String actorType,
                                         @RequestParam(required = false) String actorRole,
                                         @RequestParam(required = false) String actorName,
                                         @RequestParam(defaultValue = "false") boolean includeValues,
                                         @RequestParam(required = false) String reason,
                                         @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        String csv = service.exportCsv(new AuditFilter(tenantId, action, entityType, createdAfter, createdBefore,
                actorId, actorType, actorRole, actorName), new AuditSearchService.ValueAccess(includeValues, reason),
                UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http));
        // Cross-tenant export: there is no single clinic zone, so the filename carries the UTC date, labelled as such.
        String utcDate = Instant.now().toString().substring(0, 10);
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"audit-log-" + utcDate + "-utc.csv\"")
                .body(csv);
    }
}
