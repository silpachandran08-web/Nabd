package com.nabd.hms.reports.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * OWN-026 Reports → Packages for a period of clinic days (from/to inclusive). Revenue recognition
 * (deferred vs earned split) is deliberately not here: that is an accounting decision not yet made.
 */
public record PackagesReportResponse(
        LocalDate from,
        LocalDate to,
        List<SalesDay> salesByDay,                 // every day in the period, zero-filled
        Redemption redemption,                     // over instances sold in the period
        List<ExpiredWithValue> expiredWithValue    // validityEnd in the period, past grace today, value left
) {
    /** Packages sold on a clinic day: count of instances and their sale invoices' total (incl. tax). */
    public record SalesDay(LocalDate date, int count, BigDecimal amount) {
    }

    /** Units (all item types, same unit as liability's sessionsOwed) on instances sold in the period:
     * sold = quantity_total, redeemed = quantity_consumed so far. */
    public record Redemption(long sessionsRedeemed, long sessionsSold) {
    }

    /** remainingValue = unconsumed units x allocated per-unit price (same formula as liability). */
    public record ExpiredWithValue(UUID instanceId, String patientName, String packageName, LocalDate expiredOn,
                                   BigDecimal remainingValue) {
    }
}
