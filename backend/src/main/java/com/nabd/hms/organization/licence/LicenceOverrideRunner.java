package com.nabd.hms.organization.licence;

import com.nabd.hms.common.TenantScope;
import com.nabd.hms.organization.setup.SetupService;
import com.nabd.hms.organization.setup.dto.LicenceResponse;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

/** ADM-032: runs the override and reads the register row back in one tenant transaction (the controller holds no transaction). */
@Component
class LicenceOverrideRunner {

    private final TenantScope scope;
    private final LicenceLifecycleService lifecycle;
    private final SetupService setup;

    LicenceOverrideRunner(TenantScope scope, LicenceLifecycleService lifecycle, SetupService setup) {
        this.scope = scope;
        this.lifecycle = lifecycle;
        this.setup = setup;
    }

    LicenceResponse run(UUID tenantId, UUID licenceId, UUID staffId, String ip, LocalDate until, String reason) {
        return scope.run(tenantId, () -> {
            lifecycle.override(licenceId, staffId, ip, until, reason);
            return setup.licence(licenceId);
        });
    }
}
