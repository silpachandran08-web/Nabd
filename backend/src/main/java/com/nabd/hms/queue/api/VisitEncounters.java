package com.nabd.hms.queue.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** IDC-055: the encounter a visit (one queue_entries leg) belongs to, for the FHIR Encounter. Runs in the caller's tenant transaction. */
public interface VisitEncounters {

    /** @param encounterId queue_entries.encounter_id (the first leg); @param status this leg's status; @param startedAt when the first leg was created */
    record Encounter(UUID encounterId, UUID patientId, UUID doctorId, String status, Instant startedAt) {
    }

    /** Empty when the visit is not in the bound tenant. */
    Optional<Encounter> forVisit(UUID queueEntryId);
}
