package com.nabd.hms.billing.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * FIN-025: how the packages module gets a package sale invoiced and a package refund credited. Billing owns the invoice, its lines,
 * the tax computation (FIN-008) and the credit note (FIN-013); packages owns the allocation and the used-session valuation.
 * Every call runs in the caller's transaction.
 */
public interface PackageInvoicing {

    /** A price-list head a package component can be linked to (its tax class decides the component's tax). */
    record ChargeHead(UUID id, String code, String name, String category) {
    }

    /**
     * One component of the bundle. {@code allocatedMinor} is the component's share of the bundle price and {@code listValueMinor}
     * what it costs bought separately, both on the bundle's price basis (tax-inclusive or not). The head decides the tax class and
     * rate; without one {@code fallbackRatePercent} stands.
     */
    record Component(String code, String name, int quantity, long allocatedMinor, long listValueMinor, UUID chargeCatalogueId,
                     BigDecimal fallbackRatePercent) {
    }

    /** The invoice line of a component as issued: taxable value and tax in minor units, tax class TAXABLE / EXEMPT / NIL. */
    record Line(UUID invoiceLineId, long taxableMinor, long taxMinor, String taxCategory) {
    }

    record Issued(UUID invoiceId, String invoiceNumber, long taxMinor, long totalMinor, List<Line> lines) {
    }

    /** Active price-list heads of the clinic. */
    List<ChargeHead> chargeHeads(UUID tenantId);

    /**
     * One invoice line per component (in order), tax per line through the tax engine; with {@code taxInclusive} the tax is
     * back-calculated out of each line, so the taxable values plus the tax equal the bundle price. 409 day-closed like any invoice.
     */
    Issued issue(UUID tenantId, UUID staffId, UUID patientId, boolean taxInclusive, List<Component> components);

    /** What a credit note takes back from one invoice line. */
    record CreditLine(UUID invoiceLineId, long taxableMinor, long taxMinor) {
    }

    /** A credit note against the package invoice with one line per reversed component; lines that reverse nothing are skipped. */
    UUID creditNote(UUID tenantId, UUID staffId, UUID invoiceId, UUID seriesId, String number, List<CreditLine> lines);
}
