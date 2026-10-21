package com.nabd.hms.iam.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PLT-033: the one way other modules look up staff (name, role, clinician flag), e.g. for audit_log's actor snapshot.
 * Replaces the per-module findActorInfo/ActorInfo copies. Runs in the caller's transaction and tenant context.
 */
public interface ActorDirectory {

    record Actor(UUID id, String name, String role, boolean clinician) {}

    Optional<Actor> findActor(UUID tenantId, UUID staffId);

    /** Batch lookup in a single query; ids not found in the tenant are absent from the map. */
    Map<UUID, Actor> findActors(UUID tenantId, Collection<UUID> staffIds);

    /** Active staff flagged is_clinician (PLT-016), ordered by name. */
    List<Actor> listClinicians(UUID tenantId);
}
