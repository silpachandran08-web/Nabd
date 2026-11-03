package com.nabd.hms.pharmacy.api;

import java.util.Optional;
import java.util.UUID;

/** PHL-011: whether a customer may hand a stocked item back into stock. Runs in the caller's tenant transaction. */
public interface ReturnPolicy {

    /**
     * Why the item cannot come back (a controlled class with a statutory register such as Schedule H1, a cold-chain item, or an
     * item the clinic marked not returnable), or empty when it can.
     * @param scheduleClassAsSold the class snapshot on the invoice line (PHL-002); null = the item's current class
     */
    Optional<String> refusal(UUID itemId, String scheduleClassAsSold);
}
