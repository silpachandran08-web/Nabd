package com.nabd.hms.common;

import java.util.UUID;

/** COM-012 seam: provisioning asks messaging to instantiate the starter template catalogue for a new tenant. */
public interface CatalogueTemplates {

    /** @return how many templates were queued for background submission */
    int instantiateDefaults(UUID tenantId, String region);

    /** Saga rollback: drops the queued or submitted catalogue rows of a tenant that is being deleted. */
    void removeInstantiated(UUID tenantId);
}
