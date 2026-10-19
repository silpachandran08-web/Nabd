package com.nabd.hms.shared.phiread;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** PLT-039: the Owner's in-app read-volume alerts (polled, like break-glass alerts). No patient data. */
@RestController
class PhiReadAlertController {

    public record AlertItem(UUID id, UUID actorId, int distinctPatients, int windowMinutes, Instant createdAt) {
    }

    public record AlertsResponse(List<AlertItem> items) {
    }

    private final PhiReadRepository repo;
    private final TenantContext tenantContext;

    PhiReadAlertController(PhiReadRepository repo, TenantContext tenantContext) {
        this.repo = repo;
        this.tenantContext = tenantContext;
    }

    @GetMapping("/v1/phi-read/alerts")
    @Transactional(readOnly = true)
    public AlertsResponse phiReadAlerts(@AuthenticationPrincipal Jwt jwt) {
        String tenant = jwt.getClaimAsString("tenantId");
        if (tenant == null || !Boolean.TRUE.equals(jwt.getClaimAsBoolean("owner"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "owner-only", "Owner only", "Only the clinic Owner sees read-volume alerts.");
        }
        UUID tenantId = UUID.fromString(tenant);
        tenantContext.set(tenantId);
        return new AlertsResponse(repo.alerts(tenantId).stream()
                .map(a -> new AlertItem(a.id(), a.actorId(), a.distinctPatients(), a.windowMinutes(), a.createdAt())).toList());
    }
}
