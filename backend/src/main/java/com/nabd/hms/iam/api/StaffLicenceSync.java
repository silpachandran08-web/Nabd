package com.nabd.hms.iam.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * ADM-032: what the licence register may write to and ask of staff (iam owns the staff row). Runs in the caller's tenant
 * transaction (RLS); the tenant is never a parameter.
 */
public interface StaffLicenceSync {

    record Contact(UUID staffId, String name, String phone) {
    }

    /**
     * Sets whether the staff member is offered for online booking and, when {@code licenceExpiresOn} is not null, the licence expiry the
     * prescription-signing check reads (T-058). A date earlier than today blocks signing; an unknown staff id changes nothing.
     */
    void apply(UUID staffId, boolean bookableOnline, LocalDate licenceExpiresOn);

    /** Staff hidden from online booking (Edge, aggregator): every id whose bookable_online flag is off. */
    Set<UUID> hiddenFromOnlineBooking();

    Optional<Contact> contact(UUID staffId);

    /** Active owners of the clinic (built-in role holders), for owner-only notices. */
    List<Contact> owners();
}
