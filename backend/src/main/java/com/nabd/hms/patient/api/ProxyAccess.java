package com.nabd.hms.patient.api;

import java.util.Optional;
import java.util.UUID;

/**
 * IDC-009: what a person acting for another patient (an adult family member's proxy, or a guardian) may do. Reads the bound tenant;
 * call inside a tenant transaction. Booking, rescheduling and check-in only need a live relation (pending or active); anything that
 * shows records goes through {@link #requireRecords}. IDC-011 reads the relation to stamp "acted by".
 */
public interface ProxyAccess {

    /** @param relation the caregiver's relation to the subject ("guardian" for a minor's guardian); @param scope BOOK_ONLY, BOOK_AND_RECORDS or FULL;
     *  @param status "pending" until the adult accepts, then "active" (a guardian is always "active") */
    record Acting(String relation, String scope, String status) {
    }

    /** Who acted for the patient (IDC-011): the actor's patient id, the relation ("daughter", "guardian") and the opaque reference events carry. */
    record ActedBy(UUID patientId, String relation, String ref) {
    }

    /** The Edge names the caregiver by reference; 403 not-a-proxy unless that person has a live relation to the subject. */
    ActedBy resolve(String actorRef, UUID subjectPatientId);

    /** The desk ticked "on behalf of": the actor must be a patient of this clinic other than the subject; the relation is what the desk says. 400 acted-by-invalid. */
    ActedBy onBehalf(UUID actorPatientId, String relation, UUID subjectPatientId);

    /** One audit row, action {@code proxy.acted}, so the audit export can be filtered to proxy actions; "on" is booking, check_in, payment, cancel or reschedule. */
    void audit(ActedBy by, String on, String entityType, UUID entityId);

    /** Empty when the actor has no live relation to the subject (never granted, or revoked). A pending proxy has scope BOOK_ONLY. */
    Optional<Acting> relationFor(UUID actorPatientId, UUID subjectPatientId);

    /**
     * @throws com.nabd.hms.common.ApiException 403 proxy-consent-pending while the adult has not accepted, 403 proxy-scope-insufficient for
     *         BOOK_ONLY, 403 not-a-proxy when there is no relation
     */
    void requireRecords(UUID actorPatientId, UUID subjectPatientId);
}
