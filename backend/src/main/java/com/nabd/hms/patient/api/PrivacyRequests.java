package com.nabd.hms.patient.api;

/** PLT-047: what other modules may know about the clinic's data-subject requests (the compliance health card, PLT-050). Counts only. */
public interface PrivacyRequests {

    /** @param open requests not yet completed or rejected; @param dueSoon open with 75% of the period gone; @param overdue open past the due date */
    record Health(int open, int dueSoon, int overdue) {
    }

    /** The bound tenant's requests now; call inside a tenant transaction. */
    Health health();
}
