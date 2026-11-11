package com.nabd.hms.shared.notify;

import java.util.UUID;

/**
 * The single way to ask for a patient/staff message (COM-004). Call inside your own @Transactional business method: the
 * request row and its outbox event commit with your write or not at all. Uses the bound tenant.
 */
public interface NotificationPort {

    /**
     * @throws com.nabd.hms.common.ApiException 422 TEMPLATE_NOT_SENDABLE when the tenant has no approved template for the
     *         purpose in the request's language (thrown in the caller's transaction, so the caller's write rolls back)
     */
    NotificationOutcome request(NotificationRequest request);

    /** Cancels pending requests about a subject (appointment cancelled or moved); late consumers get 410 SUBJECT_GONE.
     * @return how many requests were cancelled */
    int cancelBySubject(String subjectType, UUID subjectId, CancelReason reason);

    /** IDC-014: does this clinic have an approved template for the purpose? Lets an event consumer skip quietly instead of failing. */
    default boolean sendable(NotificationPurpose purpose, String language) {
        return true;
    }
}
