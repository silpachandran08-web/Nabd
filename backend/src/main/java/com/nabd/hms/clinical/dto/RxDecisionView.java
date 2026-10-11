package com.nabd.hms.clinical.dto;

import java.time.Instant;
import java.util.UUID;

/** A recorded decision: the named reason, who made it and when (also written to the audit trail). */
public record RxDecisionView(String kind, String reasonCode, String reasonLabel, String note, UUID by, String byName,
                              Instant at) {
}
