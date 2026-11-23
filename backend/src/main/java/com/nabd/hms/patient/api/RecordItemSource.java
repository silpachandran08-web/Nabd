package com.nabd.hms.patient.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * COM-050: one kind of patient record the 'My records' chat menu can list and link to. The module that owns the record implements
 * it; the patient module decides who may see whose records (RecordAccess) before it calls any method here. Nothing but ids and
 * times leaves an implementation: no name, drug, test, diagnosis or amount. Every method runs in the caller's tenant transaction.
 */
public interface RecordItemSource {

    /** prescription, report or invoice: the only word the chat shows next to the date. */
    String type();

    record Item(UUID id, Instant at) {
    }

    /** The patient's items that may be shared now (not revoked, not void), newest first. */
    List<Item> latest(UUID patientId, int limit);

    record Found(UUID patientId, Instant at) {
    }

    /** Whose item this is; empty when it does not exist or may not be shared any more. */
    Optional<Found> find(UUID itemId);

    /** The stored file a secure link to the item opens, rendered and stored now if it never was. Only for an item {@link #find} returned. */
    UUID file(UUID itemId);
}
