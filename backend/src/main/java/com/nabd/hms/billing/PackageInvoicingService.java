package com.nabd.hms.billing;

import com.nabd.hms.billing.api.PackageInvoicing;
import com.nabd.hms.billing.tax.RuleBasedTaxEngine;
import com.nabd.hms.billing.tax.TaxEngine;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.EventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** FIN-025: billing.api.PackageInvoicing. The sale goes through the checkout's own invoice path, the refund through the FIN-013 credit-note tables. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class PackageInvoicingService implements PackageInvoicing {

    private final CheckoutService checkout;
    private final RefundRepository refunds;
    private final EventPublisher events;

    PackageInvoicingService(CheckoutService checkout, RefundRepository refunds, EventPublisher events) {
        this.checkout = checkout;
        this.refunds = refunds;
        this.events = events;
    }

    @Override
    public List<ChargeHead> chargeHeads(UUID tenantId) {
        return checkout.packageChargeHeads(tenantId);
    }

    @Override
    public Issued issue(UUID tenantId, UUID staffId, UUID patientId, boolean taxInclusive, List<Component> components) {
        return checkout.sellPackageComponents(tenantId, staffId, patientId, taxInclusive, components);
    }

    @Override
    public UUID creditNote(UUID tenantId, UUID staffId, UUID invoiceId, UUID seriesId, String number, List<CreditLine> lines) {
        var invoice = refunds.lockInvoice(tenantId, invoiceId).orElseThrow(() -> ApiException.notFound("Invoice not found."));
        Set<UUID> own = refunds.lines(tenantId, invoiceId).stream().map(RefundRepository.LineHead::id).collect(Collectors.toSet());
        var invoiced = refunds.lineTaxes(tenantId, invoiceId);
        List<CreditNoteMath.Reversal> reversals = new ArrayList<>();
        for (CreditLine l : lines) {
            if (l.taxableMinor() == 0 && l.taxMinor() == 0) continue;
            if (!own.contains(l.invoiceLineId())) throw new IllegalArgumentException("credit line is not a line of invoice " + invoiceId);
            // the reversed tax keeps the line's invoiced components (CGST + SGST, VAT) in their invoiced proportion
            List<TaxEngine.Component> parts = invoiced.getOrDefault(l.invoiceLineId(), List.of());
            long[] split = RuleBasedTaxEngine.allocate(l.taxMinor(), parts.stream().mapToLong(TaxEngine.Component::amountMinor).toArray());
            List<TaxEngine.Component> taxes = new ArrayList<>();
            for (int k = 0; k < split.length; k++) {
                taxes.add(new TaxEngine.Component(parts.get(k).type(), parts.get(k).ratePercent(), l.taxableMinor(), split[k]));
            }
            var reversal = new CreditNoteMath.Reversal(l.invoiceLineId().toString(), 0, l.taxableMinor(), taxes);
            if (reversal.taxMinor() != l.taxMinor()) throw new IllegalStateException("line " + l.invoiceLineId() + " was invoiced without that tax");
            reversals.add(reversal);
        }
        var note = new CreditNoteMath.Note(reversals, 0);
        // against the invoice before its round-off: a package refund never returns the round-off, earlier credit notes may have
        long credited = refunds.sumCredits(tenantId, invoiceId) - refunds.sumCreditRoundOff(tenantId, invoiceId);
        if (note.totalMinor() < 1 || credited + note.totalMinor() > invoice.totalMinor() - invoice.roundOffMinor()) {
            throw new ApiException(HttpStatus.CONFLICT, "exceeds-invoice", "Nothing left to credit",
                    "This invoice has already been credited; the refund is more than what is left on it.");
        }
        UUID creditNoteId = refunds.insertCreditNote(tenantId, invoiceId, null, seriesId, number, invoice.currency(), note, staffId);
        for (CreditNoteMath.Reversal r : reversals) refunds.insertCreditNoteLine(tenantId, creditNoteId, UUID.fromString(r.lineKey()), r);
        events.publish(tenantId, RefundService.CREDIT_NOTE_ISSUED, Map.of("tenantId", tenantId, "invoiceId", invoiceId,
                "creditNoteId", creditNoteId, "totalMinor", note.totalMinor(), "currency", invoice.currency()));
        return creditNoteId;
    }
}
