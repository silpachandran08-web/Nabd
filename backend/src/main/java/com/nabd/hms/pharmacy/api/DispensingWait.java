package com.nabd.hms.pharmacy.api;

import java.time.Instant;
import java.util.UUID;

/** ADM-042: how long signed prescriptions have been waiting at the in-house pharmacy. Runs in the caller's tenant transaction. Counts and a time only. */
public interface DispensingWait {

    /** @param oldestSince when the longest-waiting open entry reached the worklist; null when none is open */
    record Open(int open, Instant oldestSince) {
    }

    /** Worklist entries not yet dispensed (new or in progress, still valid) that arrived at or after {@code since}. */
    Open openSince(UUID tenantId, Instant since);
}
