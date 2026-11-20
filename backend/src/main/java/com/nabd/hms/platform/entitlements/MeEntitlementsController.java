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

    /** edition (PHL-032): "pharmacy" for a pharmacy-only tenant, whose navigation is pharmacy only; "clinic" for every other facility type. */
    public record MeEntitlements(List<ModuleState> modules, List<String> killedFeatures, Integer seatLimit, String edition) {
    }

    private final PlanEntitlements entitlements;

    MeEntitlementsController(PlanEntitlements entitlements) {
        this.entitlements = entitlements;
    }

    @GetMapping("/v1/me/entitlements")
    @PreAuthorize("isAuthenticated()")
    public MeEntitlements get() {
        UUID t = PlanEntitlements.currentTenant();
        List<ModuleState> mods = entitlements.modules().entrySet().stream().map(e -> new ModuleState(e.getKey(), e.getValue())).toList();
        List<String> killed = PlanEntitlements.FEATURES.stream().sorted()
                .filter(f -> entitlements.state(t, f) == PlanEntitlements.State.KILLED).toList();
        int seats = entitlements.seatLimit();
        return new MeEntitlements(mods, killed, seats == Integer.MAX_VALUE ? null : seats,
                "pharmacy".equals(entitlements.facilityType(t)) ? "pharmacy" : "clinic");
    }
}
