package com.nabd.hms.billing;

import com.nabd.hms.billing.api.PayerLedger;
import com.nabd.hms.billing.api.VisitCoverageSource;
import com.nabd.hms.billing.dto.InvoiceResponse;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.shared.money.Money;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FIN-036: the payer / patient split of an insured visit's invoice. Each line's value (after discount, tax included) is split by
 * the visit's co-pay rule: the patient's part is rounded half up to the minor unit and the payer takes the rest, so the two always
 * add up to the line. The invoice's payer share is the sum of the lines' payer parts and the patient owes total - payer share
 * (the whole-unit round-off stays with the patient, unless the patient's lines come to nothing: then the payer takes it too).
 * An uninsured visit is never touched: payer_share stays 0 and every figure is what it was before this story.
 */
@Service
public class PayerSplitService implements PayerLedger {

    private final PayerSplitRepository repo;
    private final VisitCoverageSource coverage;
    private final AuditService audit;
    private final ActorDirectory actors;

    PayerSplitService(PayerSplitRepository repo, VisitCoverageSource coverage, AuditService audit, ActorDirectory actors) {
        this.repo = repo;
        this.coverage = coverage;
        this.audit = audit;
        this.actors = actors;
    }

    /** The patient's part of one amount: percent of it, half up to the minor unit. */
    static long patientPart(long grossMinor, BigDecimal patientPercent) {
        return BigDecimal.valueOf(grossMinor).multiply(patientPercent).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValueExact();
    }

    /** The payer's share of an invoice given its lines' parts: see the class comment for the round-off rule. */
    static long payerShare(long totalMinor, long patientLinesMinor, long payerLinesMinor) {
        return patientLinesMinor == 0 ? totalMinor : Math.max(0, Math.min(payerLinesMinor, totalMinor));
    }

    /**
     * Checkout calls this after the lines and their tax are stored (first bill and every later billing stop: all lines are
     * re-split, so the invoice is always split by one rule). Nothing happens for a visit that is not insured.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(UUID tenantId, UUID visitId, UUID invoiceId) {
        var insured = coverage.insured(tenantId, visitId);
        if (insured.isEmpty()) return;
        var inv = repo.find(tenantId, invoiceId, true).orElseThrow(ApiException::notFound);
        long patientLines = 0, payerLines = 0;
        for (PayerSplitRepository.Line l : repo.lines(tenantId, invoiceId)) {
            long patient = patientPart(l.grossMinor(), insured.get().patientPercent());
            repo.setLineShares(tenantId, l.id(), patient, l.grossMinor() - patient);
            patientLines += patient;
            payerLines += l.grossMinor() - patient;
        }
        long totalMinor = Money.of(inv.total(), inv.currency()).amountMinor();
        BigDecimal payerShare = new Money(payerShare(totalMinor, patientLines, payerLines), java.util.Currency.getInstance(inv.currency())).amount();
        if (inv.payerPaid().compareTo(payerShare) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "payer-paid-exceeds-share", "Insurer already paid more",
                    "The insurer has already paid " + inv.payerPaid().toPlainString() + ", which is more than its share of the changed bill.");
        }
        BigDecimal due = inv.total().subtract(payerShare);
        if (inv.paid().compareTo(due) > 0) { // the same refusal as an uninsured bill whose new total falls below what was paid
            throw new ApiException(HttpStatus.CONFLICT, "total-below-paid", "Total is below what was paid",
                    "This invoice already has " + inv.paid().toPlainString() + " paid, which is more than the patient's share of the new total.");
        }
        String status = due.signum() == 0 && !InvoiceStatus.CLOSED.contains(inv.status()) ? "paid" : InvoiceStatus.derive(inv.status(), due, inv.paid());
        repo.setSplit(tenantId, invoiceId, insured.get().payerId(), payerShare, status);
    }

    /** The split as the invoice response shows it; null for an invoice without a payer share (the response is then unchanged). */
    @Transactional(propagation = Propagation.MANDATORY)
    public InvoiceResponse.Insurance view(UUID tenantId, UUID invoiceId) {
        var inv = repo.find(tenantId, invoiceId, false).orElse(null);
        if (inv == null || inv.payerId() == null) return null;
        List<InvoiceResponse.InsuranceLine> lines = repo.lines(tenantId, invoiceId).stream()
                .map(l -> new InvoiceResponse.InsuranceLine(l.id(), l.patientShareMinor() == null ? l.grossMinor() : l.patientShareMinor(), l.payerShareMinor()))
                .toList();
        return new InvoiceResponse.Insurance(inv.payerId(), inv.total().subtract(inv.payerShare()), inv.payerShare(), inv.payerPaid(),
                inv.pendingDecision(), lines);
    }

    /** A refund or credit note would have to be split between patient and payer, which waits for FIN-044: refused for now. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireNoPayerShare(UUID tenantId, UUID invoiceId) {
        if (repo.find(tenantId, invoiceId, false).filter(i -> i.payerShare().signum() > 0).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "insured-invoice-refund", "Insured bill",
                    "Part of this bill is the insurer's share, so it cannot be refunded here yet. Ask billing to settle it with the insurer.",
                    Map.of("code", "INSURED_INVOICE"));
        }
    }

    /** An invoice the payer has paid on cannot be voided (the desk's own payments are checked by the caller). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireNoPayerPayments(UUID tenantId, UUID invoiceId) {
        if (repo.find(tenantId, invoiceId, false).filter(i -> i.payerPaid().signum() > 0).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "invoice-has-payments", "Invoice has payments",
                    "The insurer has paid on this invoice, so it can't be voided.");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean visitBilled(UUID tenantId, UUID visitId) {
        return repo.visitBilled(tenantId, visitId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Position recordPayerPayment(UUID tenantId, UUID staffId, String ip, UUID invoiceId, BigDecimal amount, String reference) {
        var inv = repo.find(tenantId, invoiceId, true).orElseThrow(ApiException::notFound); // the lock serialises two remittances
        if (inv.payerId() == null || inv.payerShare().signum() == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "invoice-not-insured", "Not an insured bill",
                    "This bill has no insurer share, so an insurer payment cannot be recorded on it.", Map.of("code", "INVOICE_NOT_INSURED"));
        }
        if (InvoiceStatus.CLOSED.contains(inv.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "invoice-not-payable", "Invoice can't take payments",
                    "This invoice is " + inv.status() + "; it can't take payments.");
        }
        BigDecimal owed = inv.payerShare().subtract(inv.payerPaid());
        if (amount.compareTo(owed) > 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "overpayment", "Payment is more than the insurer owes",
                    "The insurer still owes " + owed.toPlainString() + " on this bill; a payment can't be more than that.",
                    Map.of("code", "PAYER_OVERPAYMENT", "balance", owed));
        }
        String ref = reference == null || reference.isBlank() ? null : reference.trim();
        UUID paymentId = repo.insertPayment(tenantId, invoiceId, inv.payerId(), amount, ref, staffId);
        repo.syncPayerPaid(tenantId, invoiceId);
        var after = repo.find(tenantId, invoiceId, false).orElseThrow();
        String[] actor = actors.findActor(tenantId, staffId).map(a -> new String[]{a.name(), a.role()}).orElse(new String[]{"Unknown", "Unknown"});
        audit.record(tenantId, "staff", staffId, actor[0], actor[1], ip, "billing.payer_payment_recorded", "invoice", invoiceId,
                Map.of("payerPaid", inv.payerPaid()),
                Map.of("paymentId", paymentId, "method", "insurance", "amount", amount, "payerPaid", after.payerPaid(),
                        "pendingDecision", after.pendingDecision()));
        return position(after);
    }

    private static Position position(PayerSplitRepository.Invoice i) {
        return new Position(i.id(), i.payerId(), i.currency(), i.payerShare(), i.payerPaid(), i.pendingDecision(), i.total().subtract(i.payerShare()));
    }
}
