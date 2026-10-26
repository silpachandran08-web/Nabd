package com.nabd.hms.patient.api;

/** IDC-003: what other modules may do with the clinic's privacy notices. Runs in the caller's tenant transaction. */
public interface PrivacyNotices {

    /**
     * Gives the clinic a v1 notice in every language its market template covers, with the grievance contact from the
     * setup consent-contact block filled in. Does nothing when the clinic already has a notice or the market has no template.
     */
    void seedInitial();
}
