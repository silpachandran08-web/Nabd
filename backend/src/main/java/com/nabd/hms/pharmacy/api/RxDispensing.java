package com.nabd.hms.pharmacy.api;

import java.util.List;
import java.util.UUID;

/**
 * PHL-018: the hybrid clinic's checkout dispense. Runs in the caller's tenant transaction. Stock still changes only through
 * {@link StockCommands}; a shortfall never makes the balance negative, it becomes a stock_discrepancies row for the next count.
 */
public interface RxDispensing {

    /** @param scheduleClass the item's class as sold (PHL-002); @param shortQty units the shelf could not cover (0 unless a shortfall reason was given);
     *  @param batchId the one batch the units came from (PHL-011), null when several or none */
    record Issued(String scheduleClass, int shortQty, UUID batchId) {
    }

    /** One stocked line of a checkout, for the dispense record. */
    record Line(String chargeCode, UUID itemId, int qty, int shortQty, String shortfallReason, UUID prescriptionItemId) {
    }

    /**
     * Takes {@code qty} off the shelf. Without a shortfall reason this is exactly {@link StockCommands#issue} (400 insufficient-stock
     * when short). With a reason it takes what is on hand and reports the rest as {@code shortQty}; a blank reason is 422 reason-required.
     */
    Issued issue(UUID itemId, int qty, UUID invoiceId, String shortfallReason);

    /** Units of {@code itemId} that were billed on the invoice but never left the shelf (shortfall); a void must not put those back. */
    int shortfallOf(UUID invoiceId, UUID itemId);

    /** Writes the dispenses row (source nabd_rx) and one stock_discrepancies row per shortfall line; no-op for an empty list.
     * PHL-012: also moves the worklist entries holding the prescription lines to partially_dispensed or dispensed. */
    void record(UUID invoiceId, UUID actorId, List<Line> lines);
}
