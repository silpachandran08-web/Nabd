package com.nabd.hms.identity.api;

/** IDC-016: ABDM checks other modules may ask for. Refuses (409 abdm-disabled) and makes no call while the clinic has ABDM off. */
public interface AbdmVerification {

    /**
     * Looks the HPR id up in the Health Professional Registry for the bound tenant.
     * Call outside a database transaction (the registry call is remote).
     *
     * @return true when the registry knows the id
     */
    boolean hprIdExists(String hprId);
}
