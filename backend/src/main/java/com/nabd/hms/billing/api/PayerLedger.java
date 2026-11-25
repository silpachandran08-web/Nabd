package com.nabd.hms.billing.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * FIN-036: what the insurance module may ask of billing about the payer's side of an invoice. Billing owns the split
 * (invoices.payer_share, the line shares) and the payer payments; nothing outside billing writes them.
 */
public interface PayerLedger {

    /**
     * The payer's side of one invoice. {@code pendingDecision} is what the payer left unpaid of its share after paying something:
     * it waits for a rejection or write-off decision (FIN-044) and is never added to {@code patientShare}.
     */
    record Position(UUID invoiceId, UUID payerId, String currency, BigDecimal payerShare, BigDecimal payerPaid,
                    BigDecimal pendingDecision, BigDecimal patientShare) {
    }

    /** True once the visit has an invoice (a void one included): its coverage can no longer change. */
    boolean visitBilled(UUID tenantId, UUID visitId);

    /**
     * Records what the payer paid against its share (method 'insurance'). 404 unknown invoice, 409 not-insured / invoice closed,
     * 422 more than the payer still owes. Runs in the caller's transaction; the caller's endpoint carries the Idempotency-Key (PLT-026).
     */
    Position recordPayerPayment(UUID tenantId, UUID staffId, String ip, UUID invoiceId, BigDecimal amount, String reference);
}
