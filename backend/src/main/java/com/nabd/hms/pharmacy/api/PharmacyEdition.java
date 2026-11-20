package com.nabd.hms.pharmacy.api;

import java.util.UUID;

/**
 * PHL-032: what provisioning asks of the pharmacy module when it creates a pharmacy-only tenant. Runs in the caller's tenant
 * transaction (the tenant is already bound).
 */
public interface PharmacyEdition {

    /** The tenant's pharmacy keeps its stock in-house (per batch) from the first day; the owner never has to switch the mode. */
    void startInHouse(UUID tenantId);
}
