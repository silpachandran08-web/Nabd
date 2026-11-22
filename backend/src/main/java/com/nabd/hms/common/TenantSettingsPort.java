package com.nabd.hms.common;

import java.util.UUID;

/**
 * PLT-036: the tenants table has one writer (platform). Clinic setup, the data gate and the clinic-day start change their own
 * clinic's row through this port without importing platform (same seam as {@link TenantLifecyclePort}). Each method runs the
 * statement the caller used to run itself, in the caller's transaction.
 */
public interface TenantSettingsPort {

    /** Clinic profile (setup step 1). specialties is never null. */
    void updateProfile(UUID tenantId, String name, String timezone, String taxId, String taxIdType, String whatsappNumber, String[] specialties);

    void markSetupCompleted(UUID tenantId);

    void updateConsentContact(UUID tenantId, String name, String email, String phone);

    /** ACC-005: minute of the day the clinic day starts. */
    void setDayStart(UUID tenantId, int minute);

    /** ADM-009: demo_only -> live; a no-op when the gate is already open (the DB trigger re-checks DPA and plan). */
    void openDataGate(UUID tenantId);
}
