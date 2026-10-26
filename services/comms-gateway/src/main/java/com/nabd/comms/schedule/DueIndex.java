package com.nabd.comms.schedule;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** COM-023.1: due-time index of scheduled sends (a Valkey sorted set mirror of the DB, which stays the truth). */
public interface DueIndex {

    record Entry(UUID tenantId, UUID messageId, Instant dueAt) {}

    void add(UUID tenantId, UUID messageId, Instant dueAt);

    void remove(UUID tenantId, UUID messageId);

    /** Entries due at or before now, oldest first. */
    List<Entry> due(Instant now, int limit);

    /** Adds every given entry (start-up and periodic reconcile from the DB). */
    void rebuild(List<Entry> all);
}
