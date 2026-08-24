package com.nabd.hms.platform.audit;

import com.nabd.hms.platform.audit.dto.AuditLogPage;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** Gated on audit_compliance:view — per NB-257's matrix, super_admin/sre/compliance_dpo. */
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
                                @RequestParam(defaultValue = "50") int limit,
                                @RequestParam(required = false) String cursor) {
        return service.search(tenantId, action, entityType, createdAfter, createdBefore, limit, cursor);
    }
}
