package com.nabd.hms.queue.api;

import java.util.UUID;

/**
 * IDC-018: a patient checks in without a staff member (ABDM Scan & Share). Same token path as the desk check-in, the entry
 * carries {@code source}. Runs in the caller's tenant transaction (TenantScope.run); the tenant is never a parameter.
 */
public interface SelfCheckIn {

    enum Refusal { CLOSED, FULL, NO_DOCTOR }

    record Token(UUID entryId, int tokenNumber) {
    }

    /** Why this doctor cannot take a self check-in right now (no session open, session cap reached, unknown doctor), if so. Never throws. */
    java.util.Optional<Refusal> refusal(UUID doctorId);

    /** The patient's open entry with this doctor today, else a new one. Call {@link #refusal} first. */
    Token checkIn(UUID patientId, UUID doctorId, String source);
}
