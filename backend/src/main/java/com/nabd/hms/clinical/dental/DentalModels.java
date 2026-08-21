package com.nabd.hms.clinical.dental;

import java.time.Instant;
import java.util.UUID;

final class DentalModels {

    private DentalModels() {
    }

    record ToothRow(UUID id, UUID patientId, int toothNumber, String status, String note, boolean isSupernumerary,
                     UUID updatedBy, Instant updatedAt) {
    }

    /** For AuditService's actor_name/actor_role snapshot fields (NB-127). */
    record ActorInfo(String name, String role) {
    }

    /** One audit_log row for a tooth's history (NB-127) — before/after are raw jsonb text, left
     * unparsed here since the frontend only needs to display them, not act on their structure. */
    record HistoryEntryRow(String actorName, String actorRole, String action, String before, String after,
                            Instant occurredAt) {
    }
}
