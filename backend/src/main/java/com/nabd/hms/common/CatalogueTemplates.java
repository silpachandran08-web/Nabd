package com.nabd.hms.common;

import java.util.UUID;

/** COM-012 seam: provisioning asks messaging to instantiate the starter template catalogue for a new tenant. */
public interface CatalogueTemplates {

    /** @return how many templates were queued for background submission */
    int instantiateDefaults(UUID tenantId, String region);

    /** PLT-022: the languages the clinic's starter catalogue was instantiated in (empty if none). Reads the bound clinic. */
    java.util.List<String> languages(UUID tenantId);

    /** PLT-022: instantiates the starter catalogue in these languages unless the clinic already has one. @return templates queued */
    int instantiateIfAbsent(UUID tenantId, java.util.List<String> languages);

    /** Saga rollback: drops the queued or submitted catalogue rows of a tenant that is being deleted. */
    void removeInstantiated(UUID tenantId);
}
