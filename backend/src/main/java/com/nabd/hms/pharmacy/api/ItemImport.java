package com.nabd.hms.pharmacy.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * ADM-013: what the data-import pipeline may ask of the pharmacy item list. Items are created exactly as the pharmacy
 * screen creates them (same schedule class, tax default, audit and catalogue event); stock itself goes in through
 * {@link StockCommands}. Runs in the caller's tenant transaction.
 */
public interface ItemImport {

    /** items: the clinic keeps a pharmacy item list (Hybrid or In-house); batches: stock is kept per batch (In-house). */
    record Mode(boolean items, boolean batches) {
    }

    /** @param taxRatePercent null = the market's default rate, as on the pharmacy screen
     *  @param rx true = prescription-only (the market's plain Rx class), false = OTC */
    record NewItem(String name, String genericName, BigDecimal price, BigDecimal mrp, BigDecimal taxRatePercent, String hsnCode,
                   Integer packSize, String unit, boolean rx) {
    }

    Mode mode();

    /** A pharmacy item with this name, ignoring case; an active one first. */
    Optional<UUID> findByName(String name);

    UUID create(NewItem item, UUID actorId);

    boolean hasBatch(UUID itemId, String batchNo, LocalDate expiryDate);
}
