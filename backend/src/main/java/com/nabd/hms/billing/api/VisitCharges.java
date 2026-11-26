package com.nabd.hms.billing.api;

import java.util.List;
import java.util.UUID;

/**
 * PHL-043: how another module puts charges on a visit's bill without writing billing tables. A pending charge is a price-list head
 * the checkout pre-loads for the visit (like a finished procedure); it is marked billed with the invoice that carries it. The
 * price and tax are the head's at checkout time. Every call runs in the caller's transaction.
 */
public interface VisitCharges {

    /** @param id the pending charge (the caller keeps it as its charge reference) */
    record Pending(UUID id, UUID chargeItemId) {
    }

    /** @param removed pending charges taken off the checkout; @param alreadyBilled charges an invoice already carries (they stay on it) */
    record Removed(int removed, int alreadyBilled) {
    }

    /** One pending charge per head, in order. {@code source} names the asking module ('lab'), {@code sourceRef} its own id (the lab order). */
    List<Pending> add(UUID tenantId, UUID visitId, String source, UUID sourceRef, List<UUID> chargeItemIds);

    /** Takes the still-pending charges of {@code sourceRef} off the checkout. Billed ones are left alone: a refund is the desk's decision. */
    Removed remove(UUID tenantId, String source, UUID sourceRef);
}
