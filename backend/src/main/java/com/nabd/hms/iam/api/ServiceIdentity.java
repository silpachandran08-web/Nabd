package com.nabd.hms.iam.api;

import java.util.Set;
import java.util.UUID;

/**
 * PLT-009: the authenticated caller of /internal/v1/** (a deployed service such as the Patient Access Edge or the Comms
 * Gateway): the {@code sub} of its aud=internal token and the clinics its token names in {@code tenants}.
 */
public record ServiceIdentity(String serviceId, Set<UUID> tenantIds) {

    public boolean mayAct(UUID tenantId) {
        return tenantIds.contains(tenantId);
    }
}
