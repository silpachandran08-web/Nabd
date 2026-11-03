package com.nabd.hms.pharmacy.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PHL-003: the only way stock changes. Every operation runs in the caller's tenant transaction (the tenant is the one
 * bound to it), locks the item row, keeps the balance at zero or above, and leaves one immutable movement row, so a
 * caller's rollback undoes the stock change with it. An unknown or non-stocked item is 404 not-found; too little
 * stock is 400 insufficient-stock.
 */
public interface StockCommands {

    /** @param scheduleClass the item's statutory class at this moment (PHL-002), for callers that snapshot it on a sale
     *  @param batchId the one batch the change touched (PHL-011: the invoice line keeps it); null when several or none */
    record StockResult(UUID itemId, int qtyBefore, int qtyAfter, String scheduleClass, UUID batchId) {
    }

    /** The stocked pharmacy item behind a charge code; empty for an ordinary (non-stocked) charge. */
    Optional<UUID> stockedItem(String chargeCode);

    /** Holds the item row lock to the end of the transaction after checking {@code qty} are on the shelf; changes nothing. */
    StockResult reserve(UUID itemId, int qty);

    /** Dispense: takes {@code qty} off the shelf for an invoice. */
    StockResult issue(UUID itemId, int qty, UUID invoiceId, String reason);

    /** Goods received: adds {@code qty}. */
    StockResult receive(UUID itemId, int qty, UUID actorId, String reason);

    /** Manual correction by a signed delta (stock-take callers compute the delta from the locked count).
     * @param reasonCode PHL-010 fixed list: damaged, count_correction, theft_loss, expired or other (the caller validates it) */
    StockResult adjust(UUID itemId, int delta, UUID actorId, String reasonCode, String reason);

    /** Puts {@code qty} back after a void or return of an invoice. */
    StockResult returnIn(UUID itemId, int qty, UUID invoiceId, UUID actorId, String reason);

    /** @param quarantined the batch had expired since the sale: the units went in as quarantined stock, never sellable */
    record ReturnedLeg(UUID batchId, int qty, boolean quarantined) {
    }

    /**
     * PHL-011: a customer's sealed units come back ('return_in' movement): into {@code batchId} (the batch the invoice line was sold
     * from) when it is this item's, else into the batches this invoice took the item from, else the implicit batch. A batch that has
     * expired since the sale is quarantined, so the returned units never count as sellable (FEFO skips non-active batches).
     */
    List<ReturnedLeg> customerReturn(UUID itemId, UUID batchId, int qty, UUID invoiceId, UUID actorId, String reason);

    // ---- PHL-005 / PHL-006: stock by batch (first-expiry-first-out) ----

    /** Goods received into a batch. In an In-house clinic batch number, expiry, MRP and purchase rate are mandatory;
     * a Hybrid clinic has one implicit batch and these fields are ignored. Month-precision expiry: pass the last day of the month. */
    record BatchReceipt(String batchNo, LocalDate expiryDate, BigDecimal mrp, BigDecimal purchaseRate, int qty, String location,
                        UUID supplierId, UUID grnLineId) {
    }

    record BatchStockResult(StockResult stock, UUID batchId) {
    }

    /** @param warning {@code expires-mid-course} when the batch runs out before the course does */
    record Pick(UUID batchId, int qty, LocalDate expiryDate, String warning) {
    }

    BatchStockResult receiveBatch(UUID itemId, BatchReceipt receipt, UUID actorId, String reason);

    /** Signed correction of one named batch (stock take). */
    StockResult adjustBatch(UUID itemId, UUID batchId, int delta, UUID actorId, String reasonCode, String reason);

    // ---- PHL-010: stock take and reasoned batch adjustments ----

    /** @param reasonCode damaged, count_correction, theft_loss, expired or other (the caller validates the list) */
    record BatchAdjustment(UUID batchId, int delta, String reasonCode, String reason) {
    }

    record AdjustedBatch(UUID batchId, UUID itemId, int delta, int qtyBefore, int qtyAfter) {
    }

    record LockedBatch(UUID itemId, int qtyOnHand) {
    }

    /** Locks the batch's item to the end of the transaction (404 when unknown) and returns the batch's quantity, for a caller that
     * turns a counted total into a delta before {@link #adjustBatches}. */
    LockedBatch lockBatch(UUID batchId);

    /** Several batch corrections at once, set-based (one statement per step, not per batch): items locked in id order, every
     * batch kept at zero or above (else 400 insufficient-stock and nothing changes), one 'adjustment' movement per batch with the
     * maker as actor and {@code approverId} as second signer (null when none was needed). A batch may appear once only. */
    List<AdjustedBatch> adjustBatches(List<BatchAdjustment> adjustments, UUID actorId, UUID approverId, String sourceType, UUID sourceId);

    /** Preview of the FEFO pick for {@code qty}, no stock change. Expired, quarantined and empty batches are never picked;
     * with {@code courseDays}, batches that expire mid-course are skipped when the rest of the stock covers the quantity.
     * 422 batch-expired when only expired stock is left, 400 insufficient-stock when there is too little. */
    List<Pick> pick(UUID itemId, int qty, Integer courseDays);

    /** Dispense from a batch the pharmacist chose instead of the FEFO one. 422 batch-expired for an expired batch; a reason is mandatory. */
    StockResult issueFromBatch(UUID itemId, UUID batchId, int qty, UUID invoiceId, UUID actorId, String overrideReason);
}
