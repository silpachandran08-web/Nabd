package com.nabd.hms.packages.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * ADM-049: what the reports module may read about sold packages. Read-only; runs in the caller's tenant transaction.
 * "Sessions" are units of every item type, the same unit as the liability report's sessions owed.
 */
public interface PackageLedgerQueries {

    /**
     * One sold package as the ledger has it now.
     *
     * @param atDesk        false only when the ledger's 'sold' event has no staff actor (a patient-channel sale, the COM-044
     *                      convention for bookings). No such sale path exists yet, so this is true for every sale today.
     * @param price         the package price as sold (tax as the package was configured; the invoice carries the tax)
     * @param lastUsableDay validity end plus grace; null while a 'first_session' package has not been used once
     * @param unusedValue   unused sessions at each item's allocated price (the liability formula), unrounded
     */
    record Sold(UUID instanceId, UUID packageId, String packageName, UUID patientId, Instant soldAt, boolean atDesk, BigDecimal price,
                long sessionsSold, long sessionsUsed, LocalDate lastUsableDay, BigDecimal unusedValue) {
    }

    /** Packages sold in [start, end), oldest first; a package refunded or cancelled later is still a sale. */
    List<Sold> soldBetween(UUID tenantId, Instant start, Instant end);

    /** Packages still active on paper whose last usable day is in [from, to] and before {@code today}, with sessions unused
     * (what the packages screen calls 'expired'); newest lapse first. */
    List<Sold> lapsedBetween(UUID tenantId, LocalDate from, LocalDate to, LocalDate today);
}
