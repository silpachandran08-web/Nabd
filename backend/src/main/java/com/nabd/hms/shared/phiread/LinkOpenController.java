package com.nabd.hms.shared.phiread;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantScope;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * PLT-041: the Owner's list of secure-link opens, newest first (what the compliance screen PLT-040 and the patient's access history read).
 * Identifiers, outcome, device family and IP only: no document content, no User-Agent string, no OTP. Owner only, like the read-volume alerts.
 */
@RestController
class LinkOpenController {

    @Schema(name = "LinkOpenItem")
    record Item(long id, Instant at, UUID linkId, UUID patientId, String resourceType, UUID resourceId, String outcome, String deviceFamily, String ip) {
    }

    @Schema(name = "LinkOpensResponse")
    record Response(List<Item> items) {
    }

    private final PhiReadRepository repo;
    private final TenantScope scope;

    LinkOpenController(PhiReadRepository repo, TenantScope scope) {
        this.repo = repo;
        this.scope = scope;
    }

    @GetMapping("/v1/phi-read/link-opens")
    Response linkOpens(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID patientId, @RequestParam(defaultValue = "50") int limit) {
        String tenant = jwt.getClaimAsString("tenantId");
        if (tenant == null || !Boolean.TRUE.equals(jwt.getClaimAsBoolean("owner"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "owner-only", "Owner only", "Only the clinic Owner sees secure-link opens.");
        }
        if (limit < 1 || limit > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "limit is 1 to 100.");
        }
        return scope.run(UUID.fromString(tenant), () -> new Response(repo.linkOpens(patientId, limit)));
    }
}
