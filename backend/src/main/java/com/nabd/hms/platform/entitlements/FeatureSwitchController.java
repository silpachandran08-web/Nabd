package com.nabd.hms.platform.entitlements;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.platform.PlatformAudit;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PLT-059: per-clinic feature overrides (on / off / kill, reason required, optional expiry) and global kill switches.
 * Gated on tenant_detail:edit (super_admin), the operator authority that already governs a clinic's lifecycle; every
 * change is audited with the reason and published as platform.entitlements.changed.v1 (per clinic).
 * shortcut: an override is replaced, not deleted (the app role has no DELETE on master); to return to the plan default let it
 * expire, or set expiresAt soon. Upgrade when operators need an explicit "clear".
 */
@RestController
public class FeatureSwitchController {

    public record OverrideRequest(@NotNull @Pattern(regexp = "on|off|kill") String state,
                                  @NotBlank @Size(max = 500) String reason, Instant expiresAt) {
    }

    public record KillRequest(boolean active, @NotBlank @Size(max = 500) String reason) {
    }

    public record OverrideResponse(String code, String state, String reason, Instant setAt, Instant expiresAt) {
    }

    private final JdbcTemplate jdbc;
    private final PlatformAudit audit;
    private final EntitlementEvents events;
    private final PlanEntitlements entitlements;
    private final Clock clock;

    FeatureSwitchController(JdbcTemplate jdbc, PlatformAudit audit, EntitlementEvents events,
                            PlanEntitlements entitlements, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.entitlements = entitlements;
        this.clock = clock;
    }

    @GetMapping("/v1/platform/tenants/{tenantId}/features")
    @PreAuthorize("hasAuthority('tenant_detail:view')")
    public List<OverrideResponse> list(@PathVariable UUID tenantId) {
        return jdbc.query("SELECT feature_code, state, reason, set_at, expires_at FROM master.tenant_feature_overrides " +
                        "WHERE tenant_id = ? ORDER BY feature_code",
                (rs, i) -> new OverrideResponse(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant(), rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant()), tenantId);
    }

    @PutMapping("/v1/platform/tenants/{tenantId}/features/{code}")
    @PreAuthorize("hasAuthority('tenant_detail:edit')")
    @Transactional
    public OverrideResponse set(@PathVariable UUID tenantId, @PathVariable String code, @Valid @RequestBody OverrideRequest req,
                                @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        requireFeature(code);
        if (Boolean.FALSE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM tenants WHERE id = ?)", Boolean.class, tenantId))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested clinic was not found.");
        }
        if (req.expiresAt() != null && !req.expiresAt().isAfter(clock.instant())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-expiry", "Invalid expiry", "expiresAt must be in the future.");
        }
        UUID operator = UUID.fromString(jwt.getSubject());
        jdbc.update("INSERT INTO master.tenant_feature_overrides (tenant_id, feature_code, state, reason, set_by, set_at, expires_at) " +
                        "VALUES (?,?,?,?,?,now(),?) ON CONFLICT (tenant_id, feature_code) DO UPDATE SET state = EXCLUDED.state, " +
                        "reason = EXCLUDED.reason, set_by = EXCLUDED.set_by, set_at = now(), expires_at = EXCLUDED.expires_at",
                tenantId, code, req.state(), req.reason(), operator, req.expiresAt() == null ? null : Timestamp.from(req.expiresAt()));
        audit.record(tenantId, operator, RequestMeta.clientIp(http), "platform_feature.override_set", "feature_override", tenantId,
                Map.of("code", code, "state", req.state(), "reason", req.reason(),
                        "expiresAt", String.valueOf(req.expiresAt())));
        events.changed(tenantId, List.of(code));
        return list(tenantId).stream().filter(o -> o.code().equals(code)).findFirst().orElseThrow();
    }

    /** Global switch: evaluated before any clinic override. Replicas converge within the 60 s snapshot TTL. */
    @PutMapping("/v1/platform/kill-switches/{code}")
    @PreAuthorize("hasAuthority('tenant_detail:edit')")
    @Transactional
    public Map<String, Object> global(@PathVariable String code, @Valid @RequestBody KillRequest req,
                                      @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        requireFeature(code);
        UUID operator = UUID.fromString(jwt.getSubject());
        jdbc.update("INSERT INTO master.kill_switches (feature_code, active, reason, set_by, set_at) VALUES (?,?,?,?,now()) " +
                        "ON CONFLICT (feature_code) DO UPDATE SET active = EXCLUDED.active, reason = EXCLUDED.reason, " +
                        "set_by = EXCLUDED.set_by, set_at = now()", code, req.active(), req.reason(), operator);
        audit.record(null, operator, RequestMeta.clientIp(http), "platform_feature.global_kill_set", "kill_switch", null,
                Map.of("code", code, "active", req.active(), "reason", req.reason()));
        entitlements.evictAll();
        return Map.of("code", code, "active", req.active());
    }

    private static void requireFeature(String code) {
        if (!PlanEntitlements.FEATURES.contains(code)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "unknown-feature", "UNKNOWN_FEATURE", "Unknown feature code.");
        }
    }
}
