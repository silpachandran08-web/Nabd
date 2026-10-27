package com.nabd.hms.clinical.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** PHL-018: the lines of the signed prescription(s) of one visit, so checkout can offer the in-house ones. */
public interface SignedRxLines {

    /** @param drugId the coded drug master id, null for a free-text line; @param quantity prescribed quantity, null when the doctor set none */
    record Line(UUID prescriptionItemId, String drugId, String drugName, BigDecimal quantity) {
    }

    /** Signed lines of the queue entry in the caller's tenant, in prescription order; drafts are never returned. */
    List<Line> forVisit(UUID queueEntryId);
}
