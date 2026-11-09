package com.nabd.hms.billing;

import tools.jackson.core.type.TypeReference;
import com.nabd.hms.billing.dto.RefundDecisionRequests;
import com.nabd.hms.billing.dto.RefundRequestRequest;
import com.nabd.hms.billing.dto.RefundRequestResponse;
import com.nabd.hms.billing.tax.TaxEngine;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.ClinicPolicies;
import com.nabd.hms.organization.api.SeriesAllocator;
import com.nabd.hms.shared.money.Money;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * FIN-013: refund request -> maker-checker approval -> tax credit note + negative payment rows. All amounts are minor units inside;
 * the API speaks major units like the rest of billing. The invoice row lock serialises request, approval and payments on one invoice.
 */
@Service
@Transactional
public class RefundService {

    static final String THRESHOLD_POLICY = "refund_owner_threshold";
    static final String CREDIT_NOTE_ISSUED = "billing.credit_note.issued.v1";
    static final String OWNER_ROLE = "Owner"; // built-in role name; a custom role cannot take it (roles are unique per tenant)
    /** Default owner-approval threshold in minor units while the clinic has no policy row: INR 2,000 / SAR 100 (FIN-013). */
    private static final Map<String, Long> DEFAULT_THRESHOLD_MINOR = Map.of("INR", 200_000L, "SAR", 10_000L, "AED", 10_000L, "QAR", 10_000L);
    private static final long FALLBACK_THRESHOLD_MINOR = 10_000L;

    private final RefundRepository repo;
    private final TenantContext tenantContext;
    private final DayCloseService dayClose;
    private final SeriesAllocator series;
    private final AuditService audit;
    private final EventPublisher events;
    private final ActorDirectory actors;
    private final ClinicPolicies policies;
    private final com.nabd.hms.pharmacy.api.ReturnPolicy returnPolicy;

    RefundService(RefundRepository repo, TenantContext tenantContext, DayCloseService dayClose, SeriesAllocator series, AuditService audit,
                  EventPublisher events, ActorDirectory actors, ClinicPolicies policies, com.nabd.hms.pharmacy.api.ReturnPolicy returnPolicy) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.dayClose = dayClose;
        this.series = series;
        this.audit = audit;
        this.events = events;
        this.actors = actors;
        this.policies = policies;
        this.returnPolicy = returnPolicy;
    }

    // ---------------------------------------------------------------- request

    @Transactional
    public RefundRequestResponse request(UUID invoiceId, UUID staffId, String ip, RefundRequestRequest req) {
        UUID tenantId = tenantContext.current();
        var invoice = repo.lockInvoice(tenantId, invoiceId).orElseThrow(() -> ApiException.notFound("Invoice not found."));
        if (InvoiceStatus.CLOSED.contains(invoice.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "invoice-not-refundable", "Invoice can't be refunded", "This invoice is " + invoice.status() + ".");
        }
        if ((req.lines() == null || req.lines().isEmpty()) == (req.amount() == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "refund-basis", "Lines or amount", "Send either the lines to reverse or an amount, not both and not neither.");
        }
        if (req.lines() != null) { // PHL-011: units go back on the shelf only when the item may be taken back
            for (var l : req.lines()) {
                if (Boolean.TRUE.equals(l.resaleable())) {
                    repo.stockedLine(tenantId, invoiceId, l.lineId()).flatMap(sl -> returnPolicy.refusal(sl.itemId(), sl.scheduleClass()))
                            .ifPresent(reason -> { throw returnNotAllowed(l.lineId(), reason); });
                }
            }
        }
        if (repo.hasPending(tenantId, invoiceId)) {
            throw new ApiException(HttpStatus.CONFLICT, "open-request-exists", "Refund already open", "This invoice already has a refund awaiting approval.",
                    Map.of("code", "OPEN_REQUEST_EXISTS"));
        }
        CreditNoteMath.Note note = compute(tenantId, invoice, req.lines(), req.amount());
        List<Target> targets = targets(tenantId, invoiceId, invoice.currency(), req.refundTo(), note.totalMinor());
        List<Map<String, Object>> storedTo = new ArrayList<>();
        targets.forEach(t -> storedTo.add(Map.of("paymentId", t.paymentId(), "amountMinor", t.amountMinor())));
        List<Map<String, Object>> storedLines = req.lines() == null ? null : req.lines().stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lineId", l.lineId());
            m.put("quantity", l.quantity());
            m.put("resaleable", Boolean.TRUE.equals(l.resaleable()));
            return m;
        }).toList();
        String noteText = req.note() == null || req.note().isBlank() ? null : req.note().trim();
        UUID id = repo.insertRequest(tenantId, invoiceId, note.totalMinor(), invoice.currency(), req.reasonCode(), noteText, storedLines, storedTo, staffId);
        record(tenantId, staffId, ip, "billing.refund_requested", id, null,
                Map.of("invoiceId", invoiceId, "amountMinor", note.totalMinor(), "reasonCode", req.reasonCode()));
        return response(repo.findRequest(tenantId, id).orElseThrow());
    }

    // ---------------------------------------------------------------- approve / reject

    @Transactional
    public RefundDecisionRequests.Approved approve(UUID requestId, UUID staffId, String ip, BigDecimal expectedAmount) {
        UUID tenantId = tenantContext.current();
        var req = repo.findRequest(tenantId, requestId).orElseThrow(() -> ApiException.notFound("Refund request not found."));
        if (req.requestedBy().equals(staffId)) { // NB-056 maker-checker
            throw new ApiException(HttpStatus.FORBIDDEN, "same-user", "Self-approval blocked",
                    "A refund must be approved by someone other than whoever requested it.", Map.of("code", "SAME_USER"));
        }
        dayClose.requireTodayOpen(tenantId); // the credit note and the cash-out land on today; today's close must not change
        var invoice = repo.lockInvoice(tenantId, req.invoiceId()).orElseThrow(() -> ApiException.notFound("Invoice not found."));
        req = repo.findRequest(tenantId, requestId).orElseThrow(() -> ApiException.notFound("Refund request not found.")); // re-read under the lock
        if (!"pending".equals(req.status())) throw alreadyDecided();
        if (req.amountMinor() > thresholdMinor(tenantId, req.currency())) {
            String role = actors.findActor(tenantId, staffId).map(ActorDirectory.Actor::role).orElse("");
            if (!OWNER_ROLE.equals(role)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "threshold-requires-owner", "Owner approval needed",
                        "Refunds above the owner threshold must be approved by the owner.", Map.of("code", "THRESHOLD_REQUIRES_OWNER"));
            }
        }
        List<RefundRequestRequest.RefundLine> lines = req.linesJson() == null ? null
                : repo.fromJson(req.linesJson(), new TypeReference<List<RefundRequestRequest.RefundLine>>() { });
        CreditNoteMath.Note note;
        List<Target> targets;
        try { // the invoice may have moved on since the request: recompute and compare
            note = compute(tenantId, invoice, lines, lines == null ? BigDecimal.valueOf(req.amountMinor(), exponent(req.currency())) : null);
            targets = storedTargets(req);
            checkTargets(tenantId, invoice.id(), targets, note.totalMinor());
        } catch (ApiException e) {
            throw amountChanged(req.amountMinor(), 0, e.getMessage());
        }
        long expectedMinor = Money.of(expectedAmount, req.currency()).amountMinor();
        if (note.totalMinor() != req.amountMinor() || expectedMinor != req.amountMinor()) {
            throw amountChanged(req.amountMinor(), note.totalMinor(), "The amount to refund changed since the request. Review it and approve again.");
        }
        var alloc = series.allocate(SeriesAllocator.Kind.CREDIT_NOTE); // gapless: taken last, rolled back with this transaction
        String number = alloc.formatted();
        UUID creditNoteId = repo.insertCreditNote(tenantId, invoice.id(), requestId, alloc.seriesId(), number, req.currency(), note, staffId);
        for (CreditNoteMath.Reversal r : note.lines()) repo.insertCreditNoteLine(tenantId, creditNoteId, UUID.fromString(r.lineKey()), r);
        Map<UUID, String> methodOf = new HashMap<>();
        repo.payments(tenantId, invoice.id()).forEach(p -> methodOf.put(p.id(), p.method()));
        Set<String> methods = new LinkedHashSet<>();
        for (Target t : targets) {
            methods.add(methodOf.get(t.paymentId()));
            repo.insertRefundPayment(tenantId, invoice.id(), methodOf.get(t.paymentId()),
                    BigDecimal.valueOf(-t.amountMinor(), exponent(req.currency())), staffId, requestId, t.paymentId());
        }
        List<Map<String, Object>> returned = new ArrayList<>();
        if (lines != null) {
            for (var l : lines) {
                if (Boolean.TRUE.equals(l.resaleable())) {
                    for (var leg : repo.restock(tenantId, invoice.id(), l.lineId(), l.quantity(), "Credit note " + number + " (resaleable return)", staffId)) {
                        returned.add(Map.of("invoiceLineId", l.lineId(), "batchId", leg.batchId(), "qty", leg.qty(), "quarantined", leg.quarantined()));
                    }
                }
            }
        }
        long creditsTotal = repo.sumCredits(tenantId, invoice.id());
        long netPaid = repo.netPaidMinor(tenantId, invoice.id());
        String status = creditsTotal >= invoice.totalMinor() ? "refunded"
                : InvoiceStatus.derive(invoice.status(), minorToAmount(invoice.totalMinor() - creditsTotal, req.currency()), minorToAmount(netPaid, req.currency()));
        repo.syncInvoice(tenantId, invoice.id(), status);
        if (repo.decide(tenantId, requestId, "approved", staffId, null) != 1) throw alreadyDecided();

        record(tenantId, staffId, ip, "billing.refund_approved", requestId, Map.of("status", "pending"),
                Map.of("status", "approved", "creditNoteId", creditNoteId, "creditNoteNumber", number, "amountMinor", note.totalMinor(), "invoiceStatus", status));
        events.publish(tenantId, DomainEventType.REFUND_APPROVED, requestId, Map.of("refundRequestId", requestId, "invoiceId", invoice.id(),
                "amountMinor", note.totalMinor(), "methods", List.copyOf(methods)));
        events.publish(tenantId, CREDIT_NOTE_ISSUED, Map.of("tenantId", tenantId, "invoiceId", invoice.id(), "creditNoteId", creditNoteId,
                "totalMinor", note.totalMinor(), "currency", req.currency()));
        if (!returned.isEmpty()) { // PHL-011: goods came back into stock with this credit note
            events.publish(tenantId, DomainEventType.PHARMACY_RETURN_ACCEPTED, creditNoteId, Map.of("invoiceId", invoice.id(), "creditId", creditNoteId,
                    "refundRequestId", requestId, "lines", returned));
        }
        return new RefundDecisionRequests.Approved(creditNoteId, number);
    }

    @Transactional
    public RefundRequestResponse reject(UUID requestId, UUID staffId, String ip, String reason) {
        UUID tenantId = tenantContext.current();
        var req = repo.findRequest(tenantId, requestId).orElseThrow(() -> ApiException.notFound("Refund request not found."));
        repo.lockInvoice(tenantId, req.invoiceId());
        if (repo.decide(tenantId, requestId, "rejected", staffId, reason.trim()) != 1) throw alreadyDecided();
        record(tenantId, staffId, ip, "billing.refund_rejected", requestId, Map.of("status", "pending"), Map.of("status", "rejected"));
        return response(repo.findRequest(tenantId, requestId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public RefundRequestResponse.Page list(String status, String cursor, int limit) {
        UUID tenantId = tenantContext.current();
        if (status != null && !Set.of("pending", "approved", "rejected").contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-status", "Invalid status", "status must be pending, approved or rejected.");
        }
        Instant beforeAt = null;
        UUID beforeId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] p = cursor.split("_", 2);
                beforeAt = Instant.parse(p[0]);
                beforeId = UUID.fromString(p[1]);
            } catch (RuntimeException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-cursor", "Invalid cursor", "The cursor isn't one this endpoint returned.");
            }
        }
        int n = Math.max(1, Math.min(limit, 100));
        var rows = repo.listRequests(tenantId, status, beforeAt, beforeId, n + 1);
        var page = rows.size() > n ? rows.subList(0, n) : rows;
        Map<UUID, ActorDirectory.Actor> names = actors.findActors(tenantId, page.stream().map(RefundRepository.RequestRow::requestedBy).toList());
        String next = rows.size() > n ? page.get(n - 1).createdAt() + "_" + page.get(n - 1).id() : null;
        return new RefundRequestResponse.Page(page.stream().map(r -> response(r, names.get(r.requestedBy()))).toList(), next);
    }

    // ---------------------------------------------------------------- computation

    /** Remaining state of each invoice line = invoiced minus earlier credit notes. */
    private List<CreditNoteMath.Remaining> open(UUID tenantId, RefundRepository.InvoiceHead invoice, List<RefundRepository.LineHead> lines) {
        var taxes = repo.lineTaxes(tenantId, invoice.id());
        var done = repo.reversed(tenantId, invoice.id());
        List<CreditNoteMath.Remaining> out = new ArrayList<>();
        for (var l : lines) {
            long taxable;
            List<TaxEngine.Component> comps;
            if (l.taxableMinor() != null) {
                taxable = l.taxableMinor();
                comps = taxes.getOrDefault(l.id(), List.of());
            } else { // shortcut: a line from before the tax engine (FIN-008) has no stored components; one TAX component at the line rate, rebuild when such invoices are migrated
                taxable = Money.of(l.lineTotal(), invoice.currency()).amountMinor();
                comps = l.ratePercent().signum() == 0 ? List.of() : List.of(new TaxEngine.Component("TAX", l.ratePercent(), taxable,
                        BigDecimal.valueOf(taxable).multiply(l.ratePercent()).movePointLeft(2).setScale(0, RoundingMode.HALF_UP).longValueExact()));
            }
            var left = new CreditNoteMath.Remaining(l.id().toString(), l.quantity(), taxable, comps);
            for (var d : done) {
                if (d.lineId().equals(l.id())) left = CreditNoteMath.after(left, new CreditNoteMath.Reversal(left.lineKey(), d.quantity(), d.taxableMinor(), d.taxes()));
            }
            out.add(left);
        }
        return out;
    }

    CreditNoteMath.Note compute(UUID tenantId, RefundRepository.InvoiceHead invoice, List<RefundRequestRequest.RefundLine> reqLines, BigDecimal amount) {
        var lines = repo.lines(tenantId, invoice.id());
        var open = open(tenantId, invoice, lines);
        long roundOffLeft = invoice.roundOffMinor() - repo.sumCreditRoundOff(tenantId, invoice.id());
        CreditNoteMath.Note note;
        if (reqLines != null && !reqLines.isEmpty()) {
            Map<String, CreditNoteMath.Remaining> byId = new HashMap<>();
            open.forEach(r -> byId.put(r.lineKey(), r));
            Map<String, Integer> qtyOf = new LinkedHashMap<>();
            for (var l : reqLines) {
                var r = byId.get(l.lineId().toString());
                if (r == null) throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "unknown-line", "Unknown line", "A line does not belong to this invoice.");
                if (qtyOf.merge(l.lineId().toString(), l.quantity(), Integer::sum) > r.quantity()) throw exceeds();
            }
            List<CreditNoteMath.Reversal> rev = new ArrayList<>();
            qtyOf.forEach((id, q) -> rev.add(CreditNoteMath.byQuantity(byId.get(id), q)));
            note = CreditNoteMath.ofQuantities(open, rev, roundOffLeft);
        } else {
            try {
                note = CreditNoteMath.byAmount(open, Money.of(amount, invoice.currency()).amountMinor(), roundOffLeft);
            } catch (IllegalArgumentException e) {
                throw exceeds();
            }
        }
        if (note.totalMinor() < 1) throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "nothing-to-refund", "Nothing to refund", "The selection reverses no value.");
        return note;
    }

    private record Target(UUID paymentId, long amountMinor) {
    }

    private List<Target> targets(UUID tenantId, UUID invoiceId, String currency, List<RefundRequestRequest.RefundTarget> given, long totalMinor) {
        List<Target> out = new ArrayList<>();
        for (var t : given) out.add(new Target(t.paymentId(), Money.of(t.amount(), currency).amountMinor()));
        checkTargets(tenantId, invoiceId, out, totalMinor);
        return out;
    }

    private List<Target> storedTargets(RefundRepository.RequestRow req) {
        List<Map<String, Object>> raw = repo.fromJson(req.refundToJson(), new TypeReference<List<Map<String, Object>>>() { });
        return raw.stream().map(m -> new Target(UUID.fromString((String) m.get("paymentId")), ((Number) m.get("amountMinor")).longValue())).toList();
    }

    /** Each payment gives back at most what it still holds, and the parts add up to the credit note. */
    private void checkTargets(UUID tenantId, UUID invoiceId, List<Target> targets, long totalMinor) {
        Map<UUID, Long> room = new HashMap<>();
        repo.payments(tenantId, invoiceId).forEach(p -> room.put(p.id(), p.amountMinor() - p.refundedMinor()));
        long sum = 0;
        Set<UUID> seen = new LinkedHashSet<>();
        for (Target t : targets) {
            if (!room.containsKey(t.paymentId()) || !seen.add(t.paymentId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "unknown-payment", "Unknown payment", "A refund-to payment does not belong to this invoice.");
            }
            if (t.amountMinor() > room.get(t.paymentId())) throw exceeds();
            sum += t.amountMinor();
        }
        if (sum != totalMinor) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "refund-to-mismatch", "Refund-to does not add up",
                    "The payments to refund must add up to the credit note total.", Map.of("code", "REFUND_TO_MISMATCH"));
        }
    }

    long thresholdMinor(UUID tenantId, String currency) {
        return policies.policyValue(tenantId, THRESHOLD_POLICY).map(Long::parseLong).orElse(DEFAULT_THRESHOLD_MINOR.getOrDefault(currency, FALLBACK_THRESHOLD_MINOR));
    }

    // ---------------------------------------------------------------- helpers

    static int exponent(String currency) {
        return java.util.Currency.getInstance(currency).getDefaultFractionDigits();
    }

    private static BigDecimal minorToAmount(long minor, String currency) {
        return BigDecimal.valueOf(minor, exponent(currency));
    }

    static ApiException exceeds() {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "exceeds-refundable", "More than can be refunded",
                "The refund is more than what is left to reverse or what was paid.", Map.of("code", "EXCEEDS_REFUNDABLE"));
    }

    static ApiException returnNotAllowed(UUID lineId, String reason) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "return-not-allowed", "Return not allowed", reason,
                Map.of("code", "RETURN_NOT_ALLOWED", "invoiceLineId", lineId, "reason", reason));
    }


    private static ApiException alreadyDecided() {
        return new ApiException(HttpStatus.CONFLICT, "already-decided", "Already decided", "This refund request has already been approved or rejected.",
                Map.of("code", "ALREADY_DECIDED"));
    }

    private static ApiException amountChanged(long requested, long current, String detail) {
        return new ApiException(HttpStatus.CONFLICT, "amount-changed", "Refund amount changed", detail,
                Map.of("code", "AMOUNT_CHANGED", "requestedAmountMinor", requested, "currentAmountMinor", current));
    }

    private void record(UUID tenantId, UUID staffId, String ip, String action, UUID entityId, Object before, Object after) {
        String[] actor = actors.findActor(tenantId, staffId).map(a -> new String[]{a.name(), a.role()}).orElse(new String[]{"Unknown", "Unknown"});
        audit.record(tenantId, "staff", staffId, actor[0], actor[1], ip, action, "refund_request", entityId, before, after);
    }

    private RefundRequestResponse response(RefundRepository.RequestRow r) {
        return response(r, actors.findActor(tenantContext.current(), r.requestedBy()).orElse(null));
    }

    private RefundRequestResponse response(RefundRepository.RequestRow r, ActorDirectory.Actor maker) {
        List<RefundRequestRequest.RefundLine> lines = r.linesJson() == null ? null
                : repo.fromJson(r.linesJson(), new TypeReference<List<RefundRequestRequest.RefundLine>>() { });
        List<RefundRequestRequest.RefundTarget> to = storedTargets(r).stream()
                .map(t -> new RefundRequestRequest.RefundTarget(t.paymentId(), minorToAmount(t.amountMinor(), r.currency()))).toList();
        return new RefundRequestResponse(r.id(), r.invoiceId(), r.invoiceNumber(), minorToAmount(r.amountMinor(), r.currency()), r.currency(),
                r.reasonCode(), r.note(), r.status(), lines, to, r.requestedBy(), maker == null ? null : maker.name(), r.decidedBy(), r.decidedAt(), r.createdAt());
    }
}
