package com.nabd.hms.clinical.api;

import java.time.Instant;
import java.util.UUID;

/** PHL-013: when a signed prescription stops being dispensable (the pharmacy module's schedule-class rule, PHL-012). */
public interface RxValidity {

    /** Null when no line's schedule class sets a validity (the Rx never expires). The caller's tenant. */
    Instant validUntil(UUID prescriptionId, UUID queueEntryId);
}
