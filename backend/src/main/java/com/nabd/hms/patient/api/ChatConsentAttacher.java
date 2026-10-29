package com.nabd.hms.patient.api;

import java.util.UUID;

/**
 * IDC-005: consent a person tapped in WhatsApp before a patient record existed. The chat registration flow calls this in the
 * transaction that creates the patient, so the decision becomes consents rows (channel WHATSAPP, wamid evidence, notice version)
 * of that patient. Runs in the caller's tenant transaction; the tenant is never a parameter.
 */
public interface ChatConsentAttacher {

    /** @param phoneHash SHA-256 hex of the phone's E.164 digits. @return how many pending decisions were attached (0 = none were waiting). */
    int attach(UUID patientId, String phoneHash);
}
