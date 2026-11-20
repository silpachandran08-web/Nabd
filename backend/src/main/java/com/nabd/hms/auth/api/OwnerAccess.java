package com.nabd.hms.auth.api;

import java.util.UUID;

/**
 * PLT-036: staff and login_attempts have one writer (iam: Java packages auth and staff). The owner sign-in records its attempts
 * and enters a clinic workspace through this API instead of writing those tables. Both run in the caller's transaction.
 */
public interface OwnerAccess {

    /** One login_attempts row for an owner sign-in attempt; ownerId is null when the email matched no owner. */
    void recordLoginAttempt(UUID ownerId, String email, String ip, boolean succeeded);

    /**
     * The staff row the owner acts through in one clinic: the existing shadow row, else the row provisioning invited under the
     * owner's email (linked and activated), else a new shadow row on the clinic's built-in role. The caller has bound the clinic
     * as the tenant of this transaction (staff and roles are RLS-protected).
     */
    UUID findOrCreateShadowStaff(UUID ownerId, UUID clinicId, String ownerName);
}
