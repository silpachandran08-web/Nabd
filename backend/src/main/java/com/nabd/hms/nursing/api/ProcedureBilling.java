package com.nabd.hms.nursing.api;

import java.util.UUID;

/** PLT-036: procedure_orders has one writer (nursing). Checkout tells nursing a visit was billed instead of updating the table. */
public interface ProcedureBilling {

    /** NB-146: every completed, not yet billed procedure order of the visit becomes billed. Runs in the caller's (checkout) transaction. */
    void markVisitBilled(UUID tenantId, UUID visitId);
}
