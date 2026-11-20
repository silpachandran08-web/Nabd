package com.nabd.hms.platform.entitlements;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** PLT-059: what staff-web needs to hide navigation for modules the clinic does not have, and to show the kill banner. */
@RestController
public class MeEntitlementsController {

    public record ModuleState(String code, boolean enabled) {
    }

    /** tenantStatus (ADM-020, additive): staff-web shows the read-only banner when suspended and the owner's payment banner when overdue. */
    public record MeEntitlements(List<ModuleState> modules, List<String> killedFeatures, Integer seatLimit, String tenantStatus) {
    }

    private final PlanEntitlements entitlements;
    private final com.nabd.hms.platform.tenant.TenantLifecycleService lifecycle;

    MeEntitlementsController(PlanEntitlements entitlements, com.nabd.hms.platform.tenant.TenantLifecycleService lifecycle) {
        this.entitlements = entitlements;
        this.lifecycle = lifecycle;
    }

    @GetMapping("/v1/me/entitlements")
    @PreAuthorize("isAuthenticated()")
    public MeEntitlements get() {
        UUID t = PlanEntitlements.currentTenant();
        List<ModuleState> mods = entitlements.modules().entrySet().stream().map(e -> new ModuleState(e.getKey(), e.getValue())).toList();
        List<String> killed = PlanEntitlements.FEATURES.stream().sorted()
                .filter(f -> entitlements.state(t, f) == PlanEntitlements.State.KILLED).toList();
        int seats = entitlements.seatLimit();
        return new MeEntitlements(mods, killed, seats == Integer.MAX_VALUE ? null : seats, lifecycle.statusOf(t).orElse(null));
    }
}
