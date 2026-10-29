package com.nabd.hms.billing;

import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.billing.dto.CheckoutContextResponse;
import com.nabd.hms.billing.dto.CheckoutRequest;
import com.nabd.hms.billing.dto.ChargeResponse;
import com.nabd.hms.billing.dto.InvoiceResponse;
import com.nabd.hms.billing.dto.LineItemResponse;
import com.nabd.hms.billing.dto.OtcChargesResponse;
import com.nabd.hms.billing.dto.PaymentRequest;
import com.nabd.hms.billing.dto.PaymentResponse;
import com.nabd.hms.billing.dto.PrescribedItemResponse;
import com.nabd.hms.billing.dto.VoidInvoiceRequest;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.organization.api.OrganizationConfig;
import com.nabd.hms.organization.api.SeriesAllocator;
import com.nabd.hms.shared.money.Money;
import com.nabd.hms.queue.QueueService;
import com.nabd.hms.queue.dto.QueueStatusUpdateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.nabd.hms.billing.CheckoutModels.ChargeRow;
import static com.nabd.hms.billing.CheckoutModels.InvoiceRow;
import static com.nabd.hms.billing.CheckoutModels.LineItemInput;
import static com.nabd.hms.billing.CheckoutModels.LineItemRow;
import static com.nabd.hms.billing.CheckoutModels.QueueEntryContext;

/**
 * NB-162: line items + quantity + discount + per-line tax -> a total that "can never be typed over"
 * (the wireframe's own words) — every figure here is derived from line items, never accepted as a
 * raw override. Round-off is to the nearest whole currency unit, matching the wireframe's example.
 * Payment recording is intentionally thin (method + amount, no gateway) — that's NB-168.
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);
    private static final Set<String> BILLING_CHECKPOINT_STATUSES = Set.of("billing_pending", "checkout_pending");

    private final CheckoutRepository repo;
    private final TenantContext tenantContext;
    private final QueueService queueService;
    private final com.nabd.hms.queue.VisitCommands visitCommands;
    private final OrganizationConfig departmentService;

    private final ClinicClock clock;

    private final DayCloseService dayClose;
    private final AuditService auditService;
    private final EventPublisher eventPublisher;
    private final BillingPricing pricing;
    private final com.nabd.hms.pharmacy.RxGate rxGate;

    private final ActorDirectory actors;
    private final com.nabd.hms.organization.api.RegionRules regions;
    private final SeriesAllocator series;
    private final com.nabd.hms.billing.tax.TaxService tax;
    private final PaymentActedBy paymentActedBy; // IDC-011
    private final com.nabd.hms.organization.api.ClinicPolicies policies;

    CheckoutService(CheckoutRepository repo, TenantContext tenantContext, QueueService queueService,
                     OrganizationConfig departmentService, ClinicClock clock, DayCloseService dayClose,
                     AuditService auditService, com.nabd.hms.queue.VisitCommands visitCommands,
                     EventPublisher eventPublisher, BillingPricing pricing, com.nabd.hms.pharmacy.RxGate rxGate, ActorDirectory actors,
                     com.nabd.hms.organization.api.RegionRules regions, SeriesAllocator series,
                     com.nabd.hms.billing.tax.TaxService tax, PaymentActedBy paymentActedBy, com.nabd.hms.organization.api.ClinicPolicies policies) {
        this.paymentActedBy = paymentActedBy;
        this.policies = policies;
        this.tax = tax;
        this.regions = regions;
        this.series = series;
        this.actors = actors;
        this.rxGate = rxGate;
        this.visitCommands = visitCommands;
        this.eventPublisher = eventPublisher;
        this.pricing = pricing;
        this.auditService = auditService;
        this.dayClose = dayClose;
        this.clock = clock;
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.queueService = queueService;
        this.departmentService = departmentService;
    }

    @Transactional
    public CheckoutContextResponse getCheckoutContext(UUID tenantId, UUID queueEntryId) {
        tenantContext.set(tenantId);
        QueueEntryContext ctx = repo.findQueueEntryContext(tenantId, queueEntryId).orElseThrow(this::notFound);
        String patientName = repo.findPatientName(tenantId, ctx.patientId()).orElse("Unknown patient");
        String doctorName = repo.findStaffName(tenantId, ctx.doctorId()).orElse("Unknown doctor");
        ConsultationTier.Context tierCtx = tierContext(tenantId, queueEntryId, ctx);
        boolean followUpEligible = tierCtx.followUpEligible();
        List<ChargeResponse> charges = repo.listActiveCharges(tenantId).stream().map(this::toChargeResponse).toList();
        List<ChargeResponse> pendingProcedures = repo.listPendingProcedures(tenantId, queueEntryId).stream()
                .map(this::toChargeResponse).toList();
        List<PrescribedItemResponse> prescribedItems = repo.findSignedPrescriptionItems(tenantId, queueEntryId).stream()
                .map(p -> new PrescribedItemResponse(p.id(), p.drugName(), p.dosage(), p.frequency(), p.duration(), p.instructions(),
                        p.quantity(), p.quantity() == null ? BigDecimal.ZERO : p.quantity().subtract(p.dispensed()).max(BigDecimal.ZERO)))
                .toList();
        return new CheckoutContextResponse(queueEntryId, patientName, doctorName,
                ctx.hasAppointment() ? "Appointment" : "Walk-in", followUpEligible, currencyFor(tenantId), charges,
                pendingProcedures, prescribedItems, ctx.departmentId(), ctx.queueStatus(),
                suggestedLines(tenantId, charges, tierCtx));
    }

    /** FIN-015/016: the follow-up window comes from the clinic policy (default 7 days), the anchor from the last full-price visit, the urgent flag from the check-in. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    private ConsultationTier.Context tierContext(UUID tenantId, UUID queueEntryId, QueueEntryContext ctx) {
        int window = policies.policyValue(tenantId, "follow_up_window_days").map(String::trim).filter(v -> v.matches("\\d{1,2}"))
                .map(Integer::parseInt).orElse(ConsultationTier.DEFAULT_WINDOW_DAYS);
        java.time.LocalDate last = repo.lastFullPriceVisit(tenantId, ctx.patientId(), ctx.doctorId());
        Integer days = last == null ? null : (int) java.time.temporal.ChronoUnit.DAYS.between(last, clock.clinicDate(tenantId));
        return new ConsultationTier.Context(ctx.doctorId(), days, window, repo.isUrgent(tenantId, queueEntryId));
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    private List<CheckoutContextResponse.SuggestedLine> suggestedLines(UUID tenantId, List<ChargeResponse> charges, ConsultationTier.Context tc) {
        List<CheckoutContextResponse.SuggestedLine> out = new java.util.ArrayList<>();
        var heads = repo.findCatalogueCharges(tenantId, charges.stream().filter(c -> ConsultationTier.isConsultation(c.category())).map(ChargeResponse::code).toList());
        for (ChargeResponse c : charges) {
            if (!ConsultationTier.isConsultation(c.category())) continue;
            BigDecimal emergency = heads.get(c.code()).emergencyAmount();
            BigDecimal fee = repo.findDoctorFee(tenantId, c.id(), tc.doctorId());
            var pick = ConsultationTier.suggest(c.category(), c.baseAmount(), fee, c.followUpAmount(), emergency, tc);
            out.add(new CheckoutContextResponse.SuggestedLine(c.code(), c.name(), pick.tier(), pick.price(), pick.reason(),
                    fee != null ? fee : c.baseAmount(), tc.followUpEligible() ? c.followUpAmount() : null, emergency));
        }
        return out;
    }

    @Transactional
    public InvoiceResponse getExistingInvoice(UUID tenantId, UUID queueEntryId) {
        tenantContext.set(tenantId);
        InvoiceRow row = repo.findInvoiceByQueueEntry(tenantId, queueEntryId).orElseThrow(this::notFound);
        return toInvoiceResponse(tenantId, row);
    }

    /**
     * NB-355: a department's flow can bill at more than one point — an interim consultation-fee
     * stop (billing_pending) as well as the terminal one (checkout_pending). The first billing
     * checkpoint for a visit creates the invoice; every later one (interim or terminal) appends to
     * the SAME invoice and recomputes its totals rather than rejecting with "invoice exists" — one
     * evolving invoice per visit, not one per checkpoint. lineItems may be empty at a later
     * checkpoint (e.g. the terminal checkout when nothing new was charged since the last stop —
     * "checkout with 0 payment"); it must be non-empty the first time a visit is billed.
     */
    @Transactional
    public InvoiceResponse checkout(UUID tenantId, UUID queueEntryId, UUID staffId, boolean canDiscount, String ip, CheckoutRequest req) {
        tenantContext.set(tenantId);
        dayClose.requireTodayOpen(tenantId);
        QueueEntryContext ctx = repo.findQueueEntryContext(tenantId, queueEntryId).orElseThrow(this::notFound);

        List<String> sequence = departmentService.resolveStatusSequence(tenantId, ctx.departmentId());
        int currentIndex = sequence.indexOf(ctx.queueStatus());
        if (!BILLING_CHECKPOINT_STATUSES.contains(ctx.queueStatus()) || currentIndex < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "not-checkout-pending", "Not ready for checkout",
                    "This visit isn't at a billing stop yet — its status is " + ctx.queueStatus() + ".");
        }

        ConsultationTier.Context tierCtx = tierContext(tenantId, queueEntryId, ctx);
        if (req.discountOrZero().signum() > 0 || !req.discountsOrEmpty().isEmpty()) BillingPricing.requireDiscountRight(canDiscount);
        List<LineItemInput> lineItems = pricing.resolveLines(tenantId, req.lineItems(), tierCtx, canDiscount, false, staffId);
        rxGate.check(tenantId, ctx.patientId(), rxLines(lineItems), staffId, ip); // PHL-001: before anything is written
        Optional<InvoiceRow> existing = repo.findInvoiceByQueueEntry(tenantId, queueEntryId);
        existing.ifPresent(CheckoutService::rejectVoid);
        UUID invoiceId;
        if (existing.isEmpty()) {
            if (lineItems.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "line-items-required", "Add at least one charge",
                        "Add at least one charge before checking out.");
            }
            Totals t = computeTotals(null, lineItems, List.of(), BigDecimal.ZERO, req, canDiscount);
            invoiceId = repo.insertInvoice(tenantId, queueEntryId, ctx.patientId(), ctx.doctorId(),
                    t.subtotal(), t.discount(), t.tax(), t.roundOff(), t.total(), staffId, series.allocate(SeriesAllocator.Kind.INVOICE));
            repo.insertLineItems(tenantId, invoiceId, t.items(), staffId);
            tax.persist(invoiceId, t.computation());
            repo.insertDiscounts(tenantId, invoiceId, t.newDiscounts(), staffId);
            auditPricing(tenantId, staffId, ip, invoiceId, lineItems, t.newDiscounts(), BigDecimal.ZERO);
            log.info("invoice {} created for queue entry {} by {} (tenant {}, total {})",
                    invoiceId, queueEntryId, staffId, tenantId, t.total());
        } else {
            invoiceId = existing.get().id();
            InvoiceRow locked = repo.lockInvoice(tenantId, invoiceId).orElseThrow(this::notFound); // serialises with payments
            List<LineItemRow> existingItems = repo.findLineItems(tenantId, invoiceId);
            BigDecimal earlier = repo.sumDiscounts(tenantId, invoiceId);
            Totals t = computeTotals(invoiceId, lineItems, existingItems, earlier, req, canDiscount);
            if (!lineItems.isEmpty()) {
                repo.appendLineItems(tenantId, invoiceId, t.items(), repo.nextLineItemOrder(tenantId, invoiceId), staffId);
            }
            tax.persist(invoiceId, t.computation());
            if (locked.paid().compareTo(t.total()) > 0) {
                throw new ApiException(HttpStatus.CONFLICT, "total-below-paid", "Total is below what was paid",
                        "This invoice already has " + locked.paid().toPlainString() + " paid, which is more than the new total.");
            }
            repo.insertDiscounts(tenantId, invoiceId, t.newDiscounts(), staffId);
            auditPricing(tenantId, staffId, ip, invoiceId, lineItems, t.newDiscounts(), earlier);
            repo.updateInvoiceTotals(tenantId, invoiceId, t.subtotal(), t.discount(), t.tax(), t.roundOff(), t.total(),
                    InvoiceStatus.derive(locked.status(), t.total(), locked.paid()));
            log.info("invoice {} extended at queue entry {} by {} (tenant {}, new total {})",
                    invoiceId, queueEntryId, staffId, tenantId, t.total());
        }
        repo.markProceduresBilled(tenantId, queueEntryId); // NB-146: whatever wasn't already billed at this visit never will be pending again

        // Closes the loop NB-102 left open: a doctor's "Complete consultation" moves a visit to
        // checkout_pending; billing finishing at the TERMINAL checkpoint is what finally moves it
        // to completed. An interim (billing_pending) checkpoint advances to whatever this
        // department's configured flow puts next — the visit continues, it doesn't end here.
        String nextStatus = sequence.get(currentIndex + 1);
        if ("completed".equals(nextStatus)) {
            visitCommands.completeAfterPayment(tenantId, staffId, queueEntryId, ctx.queueStatus());
        } else {
            queueService.updateStatus(tenantId, staffId, queueEntryId, new QueueStatusUpdateRequest(nextStatus, ctx.queueStatus()));
        }

        return toInvoiceResponse(tenantId, repo.findInvoice(tenantId, invoiceId).orElseThrow());
    }

    /** NB-186: a counter sale — no queue entry, no patient, no doctor. Same charge list, same
     * dispense-and-decrement-stock path (CheckoutRepository.insertLineItems), just no queue to close. */
    @Transactional
    public OtcChargesResponse getOtcCharges(UUID tenantId) {
        tenantContext.set(tenantId);
        requireOtcAvailable(tenantId);
        List<ChargeResponse> charges = repo.listActiveCharges(tenantId).stream().map(this::toChargeResponse).toList();
        return new OtcChargesResponse(currencyFor(tenantId), charges);
    }

    @Transactional
    public InvoiceResponse otcCheckout(UUID tenantId, UUID staffId, boolean canDiscount, String ip, CheckoutRequest req) {
        tenantContext.set(tenantId);
        requireOtcAvailable(tenantId);
        return standaloneInvoice(tenantId, staffId, null, req, "OTC", canDiscount, false, ip);
    }

    /** E14 Treatment Packages: a package sale is also a standalone invoice — no queue entry, but
     * (unlike an OTC sale) a real patient. Reuses the same total/tax/round-off computation. */
    @Transactional
    public InvoiceResponse sellPackageInvoice(UUID tenantId, UUID staffId, UUID patientId, CheckoutRequest req) {
        return standaloneInvoice(tenantId, staffId, patientId, req, "package", true, true, null);
    }

    private InvoiceResponse standaloneInvoice(UUID tenantId, UUID staffId, UUID patientId, CheckoutRequest req, String kind,
                                              boolean canDiscount, boolean trusted, String ip) {
        tenantContext.set(tenantId);
        dayClose.requireTodayOpen(tenantId);
        if (req.lineItems().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "line-items-required", "Add at least one charge",
                    "Add at least one charge before checking out.");
        }
        if (!trusted && (req.discountOrZero().signum() > 0 || !req.discountsOrEmpty().isEmpty())) BillingPricing.requireDiscountRight(canDiscount);
        List<LineItemInput> items = pricing.resolveLines(tenantId, req.lineItems(), ConsultationTier.Context.NONE, canDiscount, trusted, staffId);
        // H-04: a free-text line has no is_rx flag, so an Rx-only drug typed in by name would skip the gate. The counter sells catalogue items only.
        if (!trusted && items.stream().anyMatch(i -> i.catalogueId() == null)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "free-text-not-allowed", "Only price-list items at the counter",
                    "Counter sales can only bill items from the price list. Add the item to the price list first.");
        }
        // PHL-001: a counter sale of an Rx line needs a signed in-system prescription; the invoice then carries that patient.
        UUID rxPatient = trusted ? null : rxGate.check(tenantId, patientId, rxLines(items), staffId, ip);
        Totals t = computeTotals(null, items, List.of(), BigDecimal.ZERO, req, canDiscount);

        UUID invoiceId = repo.insertInvoice(tenantId, null, patientId != null ? patientId : rxPatient, null, t.subtotal(), t.discount(), t.tax(),
                t.roundOff(), t.total(), staffId, series.allocate(SeriesAllocator.Kind.INVOICE));
        repo.insertLineItems(tenantId, invoiceId, t.items(), staffId);
        tax.persist(invoiceId, t.computation());
        repo.insertDiscounts(tenantId, invoiceId, t.newDiscounts(), staffId);
        if (!trusted) auditPricing(tenantId, staffId, ip, invoiceId, items, t.newDiscounts(), BigDecimal.ZERO);
        log.info("{} invoice {} created by {} (tenant {}, total {})", kind, invoiceId, staffId, tenantId, t.total());

        return toInvoiceResponse(tenantId, repo.findInvoice(tenantId, invoiceId).orElseThrow());
    }

    private static List<com.nabd.hms.pharmacy.RxGate.Line> rxLines(List<LineItemInput> items) {
        return items.stream().map(i -> new com.nabd.hms.pharmacy.RxGate.Line(i.chargeCode(), i.catalogueId(), i.isRx(), i.quantity(),
                i.prescriptionItemId())).toList();
    }

    private record Totals(BigDecimal subtotal, BigDecimal discount, BigDecimal tax, BigDecimal roundOff, BigDecimal total,
                          List<BillingPricing.DiscountInput> newDiscounts, List<LineItemInput> items,
                          com.nabd.hms.billing.tax.TaxService.Computation computation) {
    }

    /** NB-355: totals over new (not-yet-persisted) line items plus, when extending an
     * already-created invoice, whatever's already on it. FIN-003: the invoice discount is the ledger sum —
     * {@code earlierDiscount} (rows from earlier checkpoints) plus this request's new rows, capped by the combined subtotal.
     * FIN-008: the discount is allocated to the lines before tax, and the tax engine returns every figure in minor units;
     * {@code items} are the new lines carrying the rate the engine resolved. */
    private Totals computeTotals(UUID invoiceId, List<LineItemInput> newItems, List<LineItemRow> existingItems,
                                 BigDecimal earlierDiscount, CheckoutRequest req, boolean canDiscount) {
        UUID tenantId = tenantContext.current();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (LineItemInput li : newItems) subtotal = subtotal.add(li.unitPrice().multiply(BigDecimal.valueOf(li.quantity())));
        for (LineItemRow li : existingItems) subtotal = subtotal.add(li.lineTotal());

        var newDiscounts = pricing.resolveDiscounts(req.discountOrZero(), req.discountsOrEmpty(), canDiscount, subtotal.subtract(earlierDiscount));
        BigDecimal discount = newDiscounts.stream().map(BillingPricing.DiscountInput::amount).reduce(earlierDiscount, BigDecimal::add);

        String currency = currencyFor(tenantId);
        List<com.nabd.hms.billing.tax.TaxService.InLine> in = new java.util.ArrayList<>();
        for (LineItemRow li : existingItems) {
            in.add(new com.nabd.hms.billing.tax.TaxService.InLine(li.id().toString(), com.nabd.hms.billing.tax.TaxService.minor(li.lineTotal(), currency),
                    null, li.taxRatePercent(), true, li.taxCategory(), li.taxInclusive(), li.interState(), li.sacHsn()));
        }
        int n = 0;
        for (LineItemInput li : newItems) {
            in.add(new com.nabd.hms.billing.tax.TaxService.InLine("new" + n++,
                    com.nabd.hms.billing.tax.TaxService.minor(li.unitPrice().multiply(BigDecimal.valueOf(li.quantity())), currency),
                    li.catalogueId(), li.taxRatePercent(), false, null, false, false, null));
        }
        var c = tax.compute(invoiceId, in, com.nabd.hms.billing.tax.TaxService.minor(discount, currency), req.buyerGstin());
        List<LineItemInput> rated = new java.util.ArrayList<>();
        for (int i = 0; i < newItems.size(); i++) rated.add(newItems.get(i).withTaxRate(c.meta().get(existingItems.size() + i).rate()));
        var r = c.result();
        return new Totals(money(r.subtotalMinor(), currency), discount, money(r.taxMinor(), currency), money(r.roundOffMinor(), currency),
                money(r.totalMinor(), currency), newDiscounts, rated, c);
    }

    private static BigDecimal money(long minor, String currency) {
        return new Money(minor, java.util.Currency.getInstance(currency)).amount();
    }

    /** FIN-008: tax for a draft cart, nothing stored. Same path as checkout, so the quote is the bill. */
    @Transactional
    public com.nabd.hms.billing.dto.QuoteResponse quote(UUID staffId, boolean canDiscount, CheckoutRequest req) {
        UUID tenantId = tenantContext.current();
        if (req.discountOrZero().signum() > 0 || !req.discountsOrEmpty().isEmpty()) BillingPricing.requireDiscountRight(canDiscount);
        List<LineItemInput> items = pricing.resolveLines(tenantId, req.lineItems(), ConsultationTier.Context.NONE, canDiscount, false, staffId);
        if (items.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "line-items-required", "Add at least one charge", "Add at least one charge to get a quote.");
        }
        Totals t = computeTotals(null, items, List.of(), BigDecimal.ZERO, req, canDiscount);
        var res = t.computation().result();
        List<com.nabd.hms.billing.dto.QuoteResponse.Line> lines = new java.util.ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            var lr = res.lines().get(i);
            lines.add(new com.nabd.hms.billing.dto.QuoteResponse.Line(items.get(i).chargeCode(), lr.discountMinor(), lr.taxableMinor(),
                    lr.taxes().stream().map(x -> new com.nabd.hms.billing.dto.QuoteResponse.Tax(x.type(), x.ratePercent(), x.amountMinor())).toList()));
        }
        return new com.nabd.hms.billing.dto.QuoteResponse(t.computation().currency(), t.computation().documentType(), lines,
                new com.nabd.hms.billing.dto.QuoteResponse.QuoteTotals(res.subtotalMinor(), res.discountMinor(), res.taxMinor(), res.roundOffMinor(), res.totalMinor()));
    }

    /** FIN-003: one audit row per price override / free-text line and per discount (before/after). */
    private void auditPricing(UUID tenantId, UUID staffId, String ip, UUID invoiceId, List<LineItemInput> items,
                              List<BillingPricing.DiscountInput> discounts, BigDecimal earlierDiscount) {
        var changed = items.stream().filter(i -> i.overriddenBy() != null).toList();
        if (changed.isEmpty() && discounts.isEmpty()) return;
        String[] actor = actors.findActor(tenantId, staffId).map(a -> new String[]{a.name(), a.role()}).orElse(new String[]{"Unknown", "Unknown"});
        for (LineItemInput i : changed) {
            auditService.record(tenantId, "staff", staffId, actor[0], actor[1], ip, "billing.price_overridden", "invoice", invoiceId,
                    java.util.Map.of("chargeCode", i.chargeCode(), "unitPrice", i.listUnitPrice()),
                    java.util.Map.of("chargeCode", i.chargeCode(), "unitPrice", i.unitPrice(), "tier", i.tier(),
                            "reasonCode", i.overrideReasonCode() == null ? "" : i.overrideReasonCode()));
        }
        BigDecimal running = earlierDiscount;
        for (var d : discounts) {
            auditService.record(tenantId, "staff", staffId, actor[0], actor[1], ip, "billing.discount_applied", "invoice", invoiceId,
                    java.util.Map.of("discount", running),
                    java.util.Map.of("discount", running.add(d.amount()), "kind", d.kind(), "value", d.value(), "reasonCode", d.reasonCode()));
            running = running.add(d.amount());
        }
    }

    private static void rejectVoid(InvoiceRow invoice) {
        if ("void".equals(invoice.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "invoice-void", "Invoice is void",
                    "This invoice was voided; it can't take payments or more charges.");
        }
    }

    @Transactional
    public InvoiceResponse recordPayment(UUID tenantId, UUID invoiceId, UUID staffId, PaymentRequest req) {
        return recordPayment(tenantId, invoiceId, staffId, req, null);
    }

    /**
     * FIN-001: the invoice row is locked first, so two cashiers queue up and each sees the other's payment. The
     * amount is checked against the locked balance, the payment row is inserted, and paid + status are re-derived
     * from the payment rows in the same transaction. A repeated Idempotency-Key returns the invoice unchanged.
     */
    @Transactional
    public InvoiceResponse recordPayment(UUID tenantId, UUID invoiceId, UUID staffId, PaymentRequest req, String idempotencyKey) {
        tenantContext.set(tenantId);
        dayClose.requireTodayOpen(tenantId);
        InvoiceRow invoice = repo.lockInvoice(tenantId, invoiceId).orElseThrow(this::notFound);
        if (idempotencyKey != null) {
            Optional<UUID> prior = repo.findInvoiceByPaymentKey(tenantId, idempotencyKey);
            if (prior.isPresent()) {
                if (!prior.get().equals(invoiceId)) {
                    throw new ApiException(HttpStatus.CONFLICT, "idempotency-key-reused", "Idempotency key reused",
                            "That Idempotency-Key was already used for a different invoice.");
                }
                return toInvoiceResponse(tenantId, invoice);
            }
        }
        if (InvoiceStatus.CLOSED.contains(invoice.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "invoice-not-payable", "Invoice can't take payments",
                    "This invoice is " + invoice.status() + "; it can't take payments.");
        }
        BigDecimal balance = invoice.total().subtract(invoice.paid());
        if (req.amount().compareTo(balance) > 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "overpayment", "Payment is more than the balance",
                    "The balance due is " + balance.toPlainString() + "; a payment can't be more than that.",
                    java.util.Map.of("balance", balance));
        }
        String reference = req.reference() == null || req.reference().isBlank() ? null : req.reference().trim();
        UUID paymentId = repo.insertPayment(tenantId, invoiceId, req.method(), req.amount(), staffId, reference, idempotencyKey,
                series.allocate(SeriesAllocator.Kind.RECEIPT).formatted());
        paymentActedBy.stamp(tenantId, paymentId, invoice.patientId(), req); // IDC-011
        BigDecimal newPaid = repo.sumPayments(tenantId, invoiceId);
        String newStatus = InvoiceStatus.derive(invoice.status(), invoice.total(), newPaid);
        repo.syncInvoicePaid(tenantId, invoiceId, newStatus);
        BigDecimal newBalance = invoice.total().subtract(newPaid);

        String[] actor = actors.findActor(tenantId, staffId).map(a -> new String[]{a.name(), a.role()}).orElse(new String[]{"Unknown", "Unknown"});
        auditService.record(tenantId, "staff", staffId, actor[0], actor[1], null, "billing.payment_recorded", "invoice", invoiceId,
                java.util.Map.of("paid", invoice.paid(), "status", invoice.status()),
                java.util.Map.of("paymentId", paymentId, "method", req.method(), "amount", req.amount(), "paid", newPaid, "status", newStatus));
        String currency = currencyFor(tenantId);
        eventPublisher.publish(tenantId, com.nabd.hms.common.DomainEventType.PAYMENT_RECORDED, paymentId, java.util.Map.of(
                "invoiceId", invoiceId, "paymentId", paymentId, "method", req.method(),
                "amountMinor", Money.of(req.amount(), currency).amountMinor(), "currency", currency,
                "balanceMinor", Money.of(newBalance, currency).amountMinor(), "status", newStatus));
        if ("paid".equals(newStatus) && !"paid".equals(invoice.status())) {
            eventPublisher.publish(tenantId, com.nabd.hms.common.DomainEventType.INVOICE_PAID, invoiceId, java.util.Map.of(
                    "invoiceId", invoiceId, "totalMinor", Money.of(invoice.total(), currency).amountMinor(), "currency", currency,
                    "status", "paid"));
        }
        log.info("payment recorded on invoice {} by {}: {} {} (tenant {})", invoiceId, staffId, req.amount(), req.method(), tenantId);
        return toInvoiceResponse(tenantId, repo.findInvoice(tenantId, invoiceId).orElseThrow());
    }

    /**
     * PHA-011: voids an invoice that has taken no money and puts its pharmacy stock back, in one transaction
     * with an immutable 'restock' movement per item and an audit event. An invoice with payments is a refund /
     * credit-note case (no such flow exists yet), so it is refused rather than silently voided.
     */
    @Transactional
    public InvoiceResponse voidInvoice(UUID tenantId, UUID invoiceId, UUID staffId, String ipAddress, VoidInvoiceRequest req) {
        tenantContext.set(tenantId);
        dayClose.requireTodayOpen(tenantId);
        var inv = repo.lockInvoiceForVoid(tenantId, invoiceId).orElseThrow(this::notFound);
        if ("void".equals(inv.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "invoice-already-void", "Invoice already void", "This invoice has already been voided.");
        }
        if (inv.paid().signum() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "invoice-has-payments", "Invoice has payments",
                    "Payments were taken on this invoice, so it can't be voided. Refund the payments first.");
        }
        String reason = req.reason().trim();
        if (reason.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "reason-required", "Reason required", "A reason is required to void an invoice.");
        }
        List<Object[]> restored = repo.restockInvoiceLines(tenantId, invoiceId, staffId, "Invoice " + inv.invoiceNumber() + " voided: " + reason);
        repo.markInvoiceVoid(tenantId, invoiceId, staffId, reason);
        String[] actor = actors.findActor(tenantId, staffId).map(a -> new String[]{a.name(), a.role()}).orElse(new String[]{"Unknown", "Unknown"});
        java.util.Map<String, Object> after = new java.util.LinkedHashMap<>();
        after.put("status", "void");
        after.put("invoiceNumber", inv.invoiceNumber());
        after.put("reason", reason);
        after.put("restocked", restored.stream().map(r -> java.util.Map.of("code", r[0], "quantity", r[1])).toList());
        auditService.record(tenantId, "staff", staffId, actor[0], actor[1], ipAddress, "billing.invoice_voided", "invoice",
                invoiceId, java.util.Map.of("status", inv.status()), after);
        return toInvoiceResponse(tenantId, repo.findInvoice(tenantId, invoiceId).orElseThrow());
    }

    @Transactional
    public java.util.List<Long> invoiceChangeSeq(UUID invoiceId) {
        return repo.changeSeq(tenantContext.current(), invoiceId);
    }

    @Transactional
    public InvoiceResponse getInvoice(UUID tenantId, UUID invoiceId) {
        tenantContext.set(tenantId);
        return toInvoiceResponse(tenantId, repo.findInvoice(tenantId, invoiceId).orElseThrow(this::notFound));
    }

    private InvoiceResponse toInvoiceResponse(UUID tenantId, InvoiceRow row) {
        var stored = tax.storedTaxes(row.id());
        List<LineItemResponse> lineItems = repo.findLineItems(tenantId, row.id()).stream()
                .map(li -> {
                    var parts = stored.getOrDefault(li.id(), List.of());
                    return new LineItemResponse(li.chargeCode(), li.chargeName(), li.category(), li.quantity(),
                        li.unitPrice(), li.taxRatePercent(), li.lineTotal(), li.listUnitPrice(), li.tier(), li.overrideReasonCode(),
                        li.taxableMinor(),
                        parts.stream().map(p -> new LineItemResponse.TaxPart(p.type(), p.ratePercent(), p.amountMinor())).toList(), li.id());
                })
                .toList();
        String[] docMeta = tax.documentMeta(row.id());
        List<PaymentResponse> payments = repo.findPayments(tenantId, row.id()).stream()
                .map(p -> new PaymentResponse(p.id(), p.method(), p.amount(), p.recordedAt(), p.idempotencyKey()))
                .toList();
        String patientName = row.patientId() == null ? "Walk-in customer"
                : repo.findPatientName(tenantId, row.patientId()).orElse("Unknown patient");
        String doctorName = row.doctorId() == null ? "—"
                : repo.findStaffName(tenantId, row.doctorId()).orElse("Unknown doctor");
        return new InvoiceResponse(row.id(), row.invoiceNumber(), row.queueEntryId(), row.patientId(), patientName,
                row.doctorId(), doctorName, currencyFor(tenantId), row.subtotal(), row.discount(), row.tax(),
                row.roundOff(), row.total(), row.paid(), row.total().subtract(row.paid()), row.status(),
                lineItems, payments, row.createdAt(),
                com.nabd.hms.billing.tax.TaxService.BILL_OF_SUPPLY.equals(docMeta[0]) ? "Bill of Supply" : "Invoice", docMeta[1]);
    }

    private ChargeResponse toChargeResponse(ChargeRow c) {
        return new ChargeResponse(c.id(), c.code(), c.name(), c.category(), c.baseAmount(), c.followUpAmount(), c.taxRatePercent(), c.stockQty(), c.isRx());
    }

    private void requireOtcAvailable(UUID tenantId) {
        if ("external".equals(repo.findPharmacyMode(tenantId).orElse("external"))) {
            throw new ApiException(HttpStatus.CONFLICT, "otc-not-available", "Counter sales not available",
                    "Counter sales aren't available while the pharmacy is external. Switch the pharmacy mode to Hybrid or In-house in Clinic Setup.");
        }
    }

    private String currencyFor(UUID tenantId) {
        return regions.region(repo.findTenantRegion(tenantId).orElse("IN")).currency();
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }
}
