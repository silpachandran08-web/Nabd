package com.nabd.hms.reports.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Reports → Today's money (DESIGN.md OWN-01) for a clinic-day period [from, to]. No-shows are
 * "from bookings vs check-ins": booked appointments whose time has passed with no check-in.
 * waitingNow is only meaningful while the period includes today (null otherwise).
 */
public record MoneyResponse(
        LocalDate from,
        LocalDate to,
        boolean includesToday,
        String currency,
        String taxLabel,
        BigDecimal collected,
        int billsRaised,
        BigDecimal taxCollected,
        int noShows,
        int bookings,
        Integer waitingNow,
        List<Tender> tender,
        List<Service> topServices
) {
    /**
     * OWN-023: count = number of payment rows recorded with this method in the period (the wireframe's
     * "9 transactions"). Exact decimals for amount are unchanged. There is no refund/reversal row in
     * invoice_payments (amount &gt; 0, no negative or void rows), so there is no per-tender refund count.
     */
    public record Tender(String method, BigDecimal amount, int count) {
    }

    /** Revenue before tax and bill-level discount — the line items as charged. */
    public record Service(String name, BigDecimal revenue) {
    }
}
