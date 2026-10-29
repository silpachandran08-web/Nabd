package com.nabd.hms.auth.api;

import java.util.List;
import java.util.UUID;

/**
 * PHL-010: maker-checker. A second staff member confirms an action on the maker's screen with their own PIN (a step-up of that
 * person, never a shared login). Reads the tenant bound to the caller's transaction. A permission counts when the staff member's
 * role or a role on loan to them (PLT-014) grants it, the same rule the access token is built with.
 */
public interface SecondSignOff {

    record Signer(UUID staffId, String name) {
    }

    enum Outcome { CONFIRMED, NOT_PERMITTED, WRONG_PIN }

    record Confirmation(Outcome outcome, Signer signer) {
    }

    /** Active staff with a PIN who hold {@code permission}, except {@code exceptStaffId}, by name. */
    List<Signer> holders(String permission, UUID exceptStaffId);

    /** NOT_PERMITTED for an unknown, inactive or unauthorised staff member (the PIN is then not tried). A wrong PIN counts toward
     * that person's lockout and is committed even when the caller rolls back; 423 account-locked while the lockout runs. */
    Confirmation confirm(UUID staffId, String pin, String permission);
}
