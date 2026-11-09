package com.nabd.hms.billing;

import com.nabd.hms.billing.dto.PharmacyReturnDtos;
import com.nabd.hms.billing.dto.RefundRequestRequest;
import com.nabd.hms.billing.dto.RefundRequestResponse;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.pharmacy.api.ReturnPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PHL-011: a customer hands sealed medicines back at the counter. The return is a FIN-013 refund request (reason customer_return,
 * every line resaleable, refunded to the bill's own payments), so it goes through the same maker-checker approval as any refund:
 * the approval issues the credit note (CreditNoteMath, tax reversed exactly), writes the refund payment rows and puts the units back
 * into the batch they were sold from ('return_in'; an expired batch is quarantined), all in one transaction. Lives in billing because
 * billing already depends on pharmacy.api; the stock rules are pharmacy's (ReturnPolicy).
 */
@Service
@Transactional
public class PharmacyReturnService {

    private static final String NOT_FOUND = "Invoice not found.";

    static final String REASON_CODE = "customer_return";
    static final String OPENED = "Opened pack: only sealed packs can be taken back.";
    static final String NOT_STOCKED = "Not a stocked pharmacy item: only medicines from stock can be returned here.";

    private final RefundRepository repo;
    private final RefundService refunds;
    private final ReturnPolicy policy;
    private final TenantContext tenantContext;

    PharmacyReturnService(RefundRepository repo, RefundService refunds, ReturnPolicy policy, TenantContext tenantContext) {
        this.repo = repo;
        this.refunds = refunds;
        this.policy = policy;
        this.tenantContext = tenantContext;
    }

    @Transactional(readOnly = true)
    public PharmacyReturnDtos.Bill bill(UUID invoiceId, String number) {
        UUID t = tenantContext.current();
        UUID id = invoiceId != null ? invoiceId
                : repo.invoiceIdByNumber(t, number == null ? "" : number.trim()).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        var invoice = repo.findInvoice(t, id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        Map<UUID, Integer> returned = returnedQty(t, id);
        List<PharmacyReturnDtos.BillLine> lines = new ArrayList<>();
        for (var l : repo.billLines(t, id)) {
            int done = returned.getOrDefault(l.id(), 0);
            int left = Math.max(0, l.quantity() - done);
            var stocked = repo.stockedLine(t, id, l.id());
            String reason = InvoiceStatus.CLOSED.contains(invoice.status()) ? "This bill is " + invoice.status() + "."
                    : stocked.isEmpty() ? NOT_STOCKED : policy.refusal(stocked.get().itemId(), stocked.get().scheduleClass()).orElse(null);
            if (reason == null && left == 0) reason = "Already returned in full.";
            lines.add(new PharmacyReturnDtos.BillLine(l.id(), l.chargeCode(), l.chargeName(), l.quantity(), done, left, l.unitPrice(),
                    l.taxRatePercent(), reason == null, reason));
        }
        LinkedHashSet<String> methods = new LinkedHashSet<>();
        repo.payments(t, id).stream().filter(p -> p.amountMinor() > p.refundedMinor()).forEach(p -> methods.add(p.method()));
        return new PharmacyReturnDtos.Bill(id, invoice.number(), invoice.status(), invoice.currency(), repo.pendingRequestId(t, id).orElse(null),
                List.copyOf(methods), lines);
    }

    public PharmacyReturnDtos.ReturnResponse create(UUID staffId, String ip, PharmacyReturnDtos.ReturnRequest req) {
        UUID t = tenantContext.current();
        // the invoice row lock serialises returns, refunds and approvals of this bill, so the sold-minus-returned check below holds
        var invoice = repo.lockInvoice(t, req.invoiceId()).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        Optional<UUID> pending = repo.pendingRequestId(t, invoice.id());
        if (pending.isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "approval-pending", "Approval pending",
                    "A return or refund of this bill is already waiting for approval. Approve or reject it first.",
                    Map.of("code", "APPROVAL_PENDING", "refundRequestId", pending.get()));
        }
        Map<UUID, Integer> sold = new HashMap<>();
        repo.lines(t, invoice.id()).forEach(l -> sold.put(l.id(), l.quantity()));
        Map<UUID, Integer> asked = new LinkedHashMap<>();
        for (var l : req.lines()) {
            if (!sold.containsKey(l.invoiceLineId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "unknown-line", "Unknown line", "A line does not belong to this invoice.");
            }
            if ("opened".equals(l.condition())) throw RefundService.returnNotAllowed(l.invoiceLineId(), OPENED);
            var stocked = repo.stockedLine(t, invoice.id(), l.invoiceLineId())
                    .orElseThrow(() -> RefundService.returnNotAllowed(l.invoiceLineId(), NOT_STOCKED));
            policy.refusal(stocked.itemId(), stocked.scheduleClass()).ifPresent(r -> {
                throw RefundService.returnNotAllowed(l.invoiceLineId(), r);
            });
            asked.merge(l.invoiceLineId(), l.qty(), Integer::sum);
        }
        Map<UUID, Integer> returned = returnedQty(t, invoice.id());
        asked.forEach((lineId, q) -> {
            int left = sold.get(lineId) - returned.getOrDefault(lineId, 0);
            if (q > left) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "return-exceeds-sold", "More than was sold",
                        "Only " + left + " unit(s) of this line can still be returned.",
                        Map.of("code", "RETURN_EXCEEDS_SOLD", "invoiceLineId", lineId, "returnableQty", left));
            }
        });
        List<RefundRequestRequest.RefundLine> lines = new ArrayList<>();
        asked.forEach((lineId, q) -> lines.add(new RefundRequestRequest.RefundLine(lineId, q, true)));
        long total = refunds.compute(t, invoice, lines, null).totalMinor();
        String note = req.note() == null || req.note().isBlank() ? null : req.note().trim();
        RefundRequestResponse r = refunds.request(invoice.id(), staffId, ip,
                new RefundRequestRequest(lines, null, REASON_CODE, note, targets(t, invoice, req.refundMethod(), total)));
        return new PharmacyReturnDtos.ReturnResponse(r.id(), r.status(), r.amount(), r.currency(), r.refundTo());
    }

    /** The refund goes back the way the bill was paid: the bill's payments (of {@code method} when given) in order, each up to what it still holds. */
    private List<RefundRequestRequest.RefundTarget> targets(UUID t, RefundRepository.InvoiceHead invoice, String method, long totalMinor) {
        var payments = repo.payments(t, invoice.id()).stream().filter(p -> method == null || method.equals(p.method())).toList();
        if (method != null && payments.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "refund-method-not-original", "Refund to the original method",
                    "The refund goes back the way the bill was paid, and it was not paid by " + method + ".", Map.of("code", "REFUND_METHOD_NOT_ORIGINAL"));
        }
        List<RefundRequestRequest.RefundTarget> out = new ArrayList<>();
        long left = totalMinor;
        for (var p : payments) {
            long take = Math.min(left, p.amountMinor() - p.refundedMinor());
            if (take <= 0) continue;
            out.add(new RefundRequestRequest.RefundTarget(p.id(), BigDecimal.valueOf(take, RefundService.exponent(invoice.currency()))));
            left -= take;
            if (left == 0) break;
        }
        if (left > 0) throw RefundService.exceeds(); // the bill was not paid enough (by that method) to give this much back
        return out;
    }

    /** Units of each line already reversed by credit notes. */
    private Map<UUID, Integer> returnedQty(UUID t, UUID invoiceId) {
        Map<UUID, Integer> out = new HashMap<>();
        repo.reversed(t, invoiceId).forEach(r -> out.merge(r.lineId(), r.quantity(), Integer::sum));
        return out;
    }

}
