package com.nabd.hms.pharmacy.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PHL-021: what pharmacy knows about an item that tax needs. Pharmacy only supplies inputs; every tax figure comes from the billing
 * tax engine. Runs in the caller's tenant transaction.
 */
public interface PharmacyTaxInputs {

    /** @param priceInclusive null = no pharmacy setting, the charge head decides; @param zeroRated medicine zero-rated by the clinic's market */
    record Inputs(Boolean priceInclusive, boolean zeroRated) {
    }

    /** Inputs of the stocked items among {@code itemIds}; an item without a pharmacy profile is absent. */
    Map<UUID, Inputs> inputs(Collection<UUID> itemIds);

    /** 422 price-above-mrp when the item has an MRP and {@code unitPrice} is above it (Drug Price Control Order); no-op otherwise. */
    void requireWithinMrp(UUID itemId, BigDecimal unitPrice);

    /** Header lines for a pharmacy invoice: one "label: number" per drug licence on file, in label order. */
    List<String> licenceLines();
}
