package com.nabd.hms.billing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Billing & Day Close (DESIGN.md owner "Billing & Day Close") for one clinic day. Amounts are the
 * invoices created and payments recorded on that day. "Unbilled" consultations are what block
 * closing; outstanding balances don't — they're acknowledged at close.
 */
public record DayCloseSummaryResponse(
        LocalDate date,
        boolean today,
        String currency,             // INR / SAR (AED / QAR once those regions exist)
        String taxLabel,             // GST (India) / VAT (Saudi Arabia)
        BigDecimal cashTolerance,    // variance beyond this needs a note
        Totals totals,
        List<Method> methods,        // cash, card, upi, other — always all four, zero when unused
        Outstanding outstanding,
        int pendingCheckouts,
        List<Unbilled> unbilled,
        List<TaxBand> taxBands,
        int splitPaymentBills,
        Close close,                 // null until the day is closed at least once
        int voidedCount,             // OWN-019: invoices whose voided_at falls in this day (whenever they were raised)
        BigDecimal voidedTotal,      // sum of those invoices' total
        List<Method> refunds,        // FIN-013: money handed back today by method (positive amounts); methods[] and expected cash already net them
        CreditNotes creditNotes      // PHL-011: credit notes issued today (refunds and pharmacy returns), tax reversed included
) {
    /** Positive amounts in major units; tax is the output tax the day's credit notes reversed. */
    public record CreditNotes(int count, BigDecimal taxable, BigDecimal tax, BigDecimal total) {
    }

    public record Totals(BigDecimal billed, int bills, BigDecimal collected, int payments, BigDecimal tax) {
    }

    public record Method(String method, BigDecimal amount, int transactions) {
    }

    public record Outstanding(BigDecimal amount, int patients, int bills) {
    }

    /** A consultation that ended (checkout pending or completed) with no bill yet. */
    public record Unbilled(UUID queueEntryId, int token, String patientName, String doctorName, Instant endedAt) {
    }

    /** Taxable value and tax per rate; the tax column adds up to totals.tax. */
    public record TaxBand(BigDecimal ratePercent, BigDecimal taxable, BigDecimal tax) {
    }

    public record Close(String status, BigDecimal expectedCash, BigDecimal countedCash, BigDecimal variance, String note,
                        String closedByName, Instant closedAt, String reopenedByName, Instant reopenedAt, String reopenReason,
                        int closeCount) {
    }
}
