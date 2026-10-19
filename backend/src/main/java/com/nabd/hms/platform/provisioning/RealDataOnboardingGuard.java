package com.nabd.hms.platform.provisioning;

import com.nabd.hms.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * OPS-048: no real patient data before the Phase 0 exit gate. While blocked, the provisioning flow refuses to create or
 * advance tenants. Mode "blocked" or "open"; unset means blocked under the prod profile (prod-in) and open elsewhere.
 * Switch to "open" only after docs/gates/phase0.md records a passed gate.
 */
@Component
public class RealDataOnboardingGuard {

    private final boolean blocked;

    RealDataOnboardingGuard(Environment env, @Value("${app.provisioning.real-data-onboarding:${REAL_DATA_ONBOARDING:}}") String mode) {
        this.blocked = switch (mode.trim()) {
            case "blocked" -> true;
            case "open" -> false;
            case "" -> env.acceptsProfiles(Profiles.of("prod"));
            default -> throw new IllegalStateException("app.provisioning.real-data-onboarding must be 'blocked' or 'open'");
        };
    }

    public boolean blocked() {
        return blocked;
    }

    void check() {
        if (blocked) {
            throw new ApiException(HttpStatus.CONFLICT, "real-data-onboarding-blocked", "Real-data onboarding is blocked",
                    "The Phase 0 exit gate has not passed (docs/gates/phase0.md), so tenant provisioning is disabled in this environment");
        }
    }
}
