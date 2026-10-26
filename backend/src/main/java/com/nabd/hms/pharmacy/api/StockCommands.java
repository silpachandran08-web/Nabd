package com.nabd.hms.pharmacy.api;

import java.util.Optional;
import java.util.UUID;

/**
 * PHL-003: the only way stock changes. Every operation runs in the caller's tenant transaction (the tenant is the one
 * bound to it), locks the item row, keeps the balance at zero or above, and leaves one immutable movement row, so a
 * caller's rollback undoes the stock change with it. An unknown or non-stocked item is 404 not-found; too little
 * stock is 400 insufficient-stock.
 */
public interface StockCommands {

    /** @param scheduleClass the item's statutory class at this moment (PHL-002), for callers that snapshot it on a sale */
    record StockResult(UUID itemId, int qtyBefore, int qtyAfter, String scheduleClass) {
    }

    /** The stocked pharmacy item behind a charge code; empty for an ordinary (non-stocked) charge. */
    Optional<UUID> stockedItem(String chargeCode);

    /** Holds the item row lock to the end of the transaction after checking {@code qty} are on the shelf; changes nothing. */
    StockResult reserve(UUID itemId, int qty);

    /** Dispense: takes {@code qty} off the shelf for an invoice. */
    StockResult issue(UUID itemId, int qty, UUID invoiceId, String reason);

    /** Goods received: adds {@code qty}. */
    StockResult receive(UUID itemId, int qty, UUID actorId, String reason);

    /** Manual correction by a signed delta (stock-take callers compute the delta from the locked count). */
    StockResult adjust(UUID itemId, int delta, UUID actorId, String reason);

    /** Puts {@code qty} back after a void or return of an invoice. */
    StockResult returnIn(UUID itemId, int qty, UUID invoiceId, UUID actorId, String reason);
}
