package com.nabd.hms.packages;

import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.billing.CheckoutService;
import com.nabd.hms.billing.api.PackageInvoicing;
import com.nabd.hms.billing.dto.CheckoutRequest;
import com.nabd.hms.billing.dto.InvoiceResponse;
import com.nabd.hms.billing.dto.LineItemRequest;
import com.nabd.hms.billing.dto.PaymentRequest;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.packages.dto.ExpiringSoonResponse;
import com.nabd.hms.packages.dto.ExtendRequest;
import com.nabd.hms.packages.dto.InstanceEventResponse;
import com.nabd.hms.packages.dto.InstanceItemResponse;
import com.nabd.hms.packages.dto.InstanceResponse;
import com.nabd.hms.packages.dto.LiabilityResponse;
import com.nabd.hms.packages.dto.RefundPreviewItem;
import com.nabd.hms.packages.dto.RefundPreviewLine;
import com.nabd.hms.packages.dto.RefundPreviewResponse;
import com.nabd.hms.packages.dto.RefundRequestRequest;
import com.nabd.hms.packages.dto.RefundResponse;
import com.nabd.hms.packages.dto.SellPackageRequest;
import com.nabd.hms.shared.money.Money;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.nabd.hms.packages.PackageModels.InstanceItemRow;
import static com.nabd.hms.packages.PackageModels.InstanceRow;
import static com.nabd.hms.packages.PackageModels.LiabilityRow;
import static com.nabd.hms.packages.PackageModels.PackageItemRow;
import static com.nabd.hms.packages.PackageModels.PackageRow;
import static com.nabd.hms.packages.PackageModels.RefundRow;

/**
 * NB-152/153/154/157/158/160/161: everything that happens to a package once it's been sold — the
 * sale itself, the per-item session ledger (book vs redeem, per NB-154 only redemption decrements),
 * expiry/extension, refunds and the liability report. One service because they all share the same
 * instance/ledger repository calls, mirroring CheckoutService's own breadth (checkout + payments +
 * invoices in one place).
 *
 * Deliberately NOT built here (all "Phase 2" in the wireframe's own Package settings panel, agreeing
 * with the xlsx's Delivery Phase column): scheduled instalments (NB-156) and transfer/gifting (NB-159).
 * Messages (package card, Send reminder, expiry warnings, next-session nudges) go through PackageMessagingService (COM-044).
 */
@Service
public class PackageInstanceService {

    // ascending, so the first match is the tightest (most urgent) applicable tier
    private static final List<Integer> ALERT_TIERS = List.of(7, 14, 30);

    private final PackageRepository repo;
    private final TenantContext tenantContext;
    private final CheckoutService checkoutService;
    private final AuditService auditService;
    private final com.nabd.hms.common.EventPublisher eventPublisher;

    private final ClinicClock clock;
    private final com.nabd.hms.billing.DayCloseService dayClose;

    private final ActorDirectory actors;

    private final com.nabd.hms.organization.api.SeriesAllocator series;
    private final PackageMessagingService messaging;
    private final PackageInvoicing billing;
    private final com.nabd.hms.organization.api.RegionRules regions;

    PackageInstanceService(PackageRepository repo, TenantContext tenantContext, CheckoutService checkoutService,
                            AuditService auditService, ClinicClock clock, com.nabd.hms.common.EventPublisher eventPublisher, ActorDirectory actors,
                            com.nabd.hms.billing.DayCloseService dayClose,
                            com.nabd.hms.organization.api.SeriesAllocator series, PackageMessagingService messaging,
                            PackageInvoicing billing, com.nabd.hms.organization.api.RegionRules regions) {
        this.billing = billing;
        this.regions = regions;
        this.messaging = messaging;
        this.series = series;
        this.actors = actors;
        this.dayClose = dayClose;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.checkoutService = checkoutService;
        this.auditService = auditService;
    }

    @Transactional
    public InstanceResponse sell(UUID tenantId, UUID staffId, SellPackageRequest req) {
        tenantContext.set(tenantId);
        PackageRow pkg = repo.findPackage(tenantId, req.packageId()).orElseThrow(ApiException::notFound);
        if (!"on_sale".equals(pkg.status())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "package-not-on-sale", "Package not on sale",
                    "This package isn't on sale right now.");
        }
        if (repo.hasActiveInstance(tenantId, req.patientId(), req.packageId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "duplicate-active-package", "Conflicting active package",
                    "This patient already has an active copy of this package.");
        }
        List<PackageItemRow> items = repo.listItems(tenantId, req.packageId());
        var region = regions.region(clock.region(tenantId));
        String currency = region.currency();

        record Allocated(PackageItemRow item, UUID id, BigDecimal allocatedPrice, PackageInvoicing.Line line) {
        }
        List<Allocated> allocations = new ArrayList<>();
        UUID invoiceId;
        String invoiceNumber;
        BigDecimal soldTax;
        BigDecimal total;
        if (taxSplitOn(tenantId, region)) {
            // FIN-025: the invoice is generated from the components, each at its allocated share with its own tax
            long[] listValue = items.stream()
                    .mapToLong(i -> Money.of(i.unitListPrice().multiply(BigDecimal.valueOf(i.quantity())), currency).amountMinor()).toArray();
            long[] share = PackageSplit.allocate(Money.of(pkg.price(), currency).amountMinor(), listValue);
            List<UUID> ids = items.stream().map(i -> UUID.randomUUID()).toList();
            List<PackageInvoicing.Component> components = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                PackageItemRow item = items.get(i);
                components.add(new PackageInvoicing.Component("PKGI-" + ids.get(i).toString().substring(0, 8).toUpperCase(), item.name(),
                        item.quantity(), share[i], listValue[i], item.chargeCatalogueId(), item.taxRatePercent()));
            }
            PackageInvoicing.Issued issued = billing.issue(tenantId, staffId, req.patientId(), pkg.taxInclusive(), components);
            for (int i = 0; i < items.size(); i++) {
                allocations.add(new Allocated(items.get(i), ids.get(i), amount(share[i], currency), issued.lines().get(i)));
            }
            invoiceId = issued.invoiceId();
            invoiceNumber = issued.invoiceNumber();
            soldTax = amount(issued.taxMinor(), currency);
            total = amount(issued.totalMinor(), currency);
        } else {
            BigDecimal listTotal = items.stream().map(i -> i.unitListPrice().multiply(BigDecimal.valueOf(i.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            List<UUID> ids = new ArrayList<>();
            List<BigDecimal> shares = new ArrayList<>();
            List<LineItemRequest> lineItems = new ArrayList<>();
            BigDecimal allocatedSoFar = BigDecimal.ZERO;
            for (int i = 0; i < items.size(); i++) {
                PackageItemRow item = items.get(i);
                BigDecimal listShare = item.unitListPrice().multiply(BigDecimal.valueOf(item.quantity()));
                BigDecimal allocated;
                if (i == items.size() - 1) {
                    allocated = pkg.price().subtract(allocatedSoFar); // last item absorbs rounding drift
                } else {
                    allocated = pkg.price().multiply(listShare).divide(listTotal, 2, RoundingMode.HALF_UP);
                    allocatedSoFar = allocatedSoFar.add(allocated);
                }
                BigDecimal perUnitAllocated = allocated.divide(BigDecimal.valueOf(item.quantity()), 4, RoundingMode.HALF_UP);
                BigDecimal exTaxUnitPrice = pkg.taxInclusive()
                        ? perUnitAllocated.divide(BigDecimal.ONE.add(item.taxRatePercent().divide(BigDecimal.valueOf(100))), 2, RoundingMode.HALF_UP)
                        : perUnitAllocated.setScale(2, RoundingMode.HALF_UP);
                UUID id = UUID.randomUUID();
                ids.add(id);
                shares.add(allocated);
                lineItems.add(new LineItemRequest("PKGI-" + id.toString().substring(0, 8).toUpperCase(),
                        item.name(), "Package", item.quantity(), exTaxUnitPrice, item.taxRatePercent()));
            }
            InvoiceResponse invoice = checkoutService.sellPackageInvoice(tenantId, staffId, req.patientId(),
                    new CheckoutRequest(lineItems, BigDecimal.ZERO));
            for (int i = 0; i < items.size(); i++) {
                // the line as invoiced, so a refund reverses exactly what this component was charged
                var li = invoice.lineItems().get(i);
                long tax = li.taxes().stream().mapToLong(t -> t.amountMinor()).sum();
                long taxable = li.taxableMinor() != null ? li.taxableMinor() : Money.of(li.lineTotal(), currency).amountMinor();
                allocations.add(new Allocated(items.get(i), ids.get(i), shares.get(i),
                        new PackageInvoicing.Line(li.id(), taxable, tax, tax > 0 ? "TAXABLE" : "NIL")));
            }
            invoiceId = invoice.id();
            invoiceNumber = invoice.invoiceNumber();
            soldTax = invoice.tax();
            total = invoice.total();
        }
        checkoutService.recordPayment(tenantId, invoiceId, staffId, new PaymentRequest(req.paymentMethod(), total));

        LocalDate today = clock.today(tenantId);
        boolean startsNow = "purchase_date".equals(pkg.validityStarts());
        LocalDate validityStart = startsNow ? today : null;
        LocalDate validityEnd = startsNow ? today.plusDays(pkg.validityDays()) : null;

        UUID instanceId = UUID.randomUUID();
        repo.insertInstance(instanceId, tenantId, pkg.id(), req.patientId(), invoiceId, pkg.name(),
                pkg.price(), soldTax, pkg.validityStarts(), pkg.validityDays(), validityStart, validityEnd,
                pkg.graceDays(), staffId, pkg.taxInclusive());
        for (Allocated a : allocations) {
            repo.insertInstanceItem(a.id(), tenantId, instanceId, a.item().itemType(), a.item().name(),
                    a.item().quantity(), a.item().unitListPrice(), a.allocatedPrice(), a.item().taxRatePercent(),
                    a.line().invoiceLineId(), a.line().taxableMinor(), a.line().taxMinor(), a.line().taxCategory());
        }
        repo.insertEvent(tenantId, instanceId, "sold", pkg.name() + " · " + pkg.price(), null, staffId);
        repo.insertEvent(tenantId, instanceId, "payment_received", req.paymentMethod(), null, staffId);
        repo.insertEvent(tenantId, instanceId, "invoice_issued", "GST invoice · " + invoiceNumber, null, staffId);
        messaging.sold(tenantId, instanceId, req.patientId(), req.nudgeIntervalDays()); // COM-044: the card goes out from the event

        return detail(tenantId, instanceId);
    }

    @Transactional
    public List<InstanceResponse> list(UUID tenantId, UUID patientId) {
        tenantContext.set(tenantId);
        return repo.listInstances(tenantId, patientId).stream().map(row -> toResponse(tenantId, row, false)).toList();
    }

    @Transactional
    public InstanceResponse detail(UUID tenantId, UUID id) {
        tenantContext.set(tenantId);
        InstanceRow row = repo.findInstance(tenantId, id).orElseThrow(ApiException::notFound);
        return toResponse(tenantId, row, true);
    }

    @Transactional
    public InstanceResponse book(UUID tenantId, UUID staffId, UUID instanceItemId) {
        tenantContext.set(tenantId);
        InstanceItemRow item = repo.findInstanceItem(tenantId, instanceItemId).orElseThrow(ApiException::notFound);
        UUID instanceId = repo.findInstanceIdForItem(tenantId, instanceItemId).orElseThrow(ApiException::notFound);
        repo.lockInstance(tenantId, instanceId); // FIN-004: serialise with refund request/approve
        InstanceRow instance = repo.findInstance(tenantId, instanceId).orElseThrow(ApiException::notFound);
        requireActionable(tenantId, instance, item, clock.today(tenantId));

        repo.insertRedemption(tenantId, instanceItemId, staffId);
        repo.insertEvent(tenantId, instanceId, "session_booked", item.name() + " · no entitlement consumed on booking", 0, staffId);
        return detail(tenantId, instanceId);
    }

    @Transactional
    public InstanceResponse redeem(UUID tenantId, UUID staffId, UUID instanceItemId) {
        tenantContext.set(tenantId);
        InstanceItemRow item = repo.findInstanceItem(tenantId, instanceItemId).orElseThrow(ApiException::notFound);
        UUID instanceId = repo.findInstanceIdForItem(tenantId, instanceItemId).orElseThrow(ApiException::notFound);
        repo.lockInstance(tenantId, instanceId); // FIN-004: serialise with refund request/approve
        InstanceRow instance = repo.findInstance(tenantId, instanceId).orElseThrow(ApiException::notFound);
        requireActionable(tenantId, instance, item, clock.today(tenantId));

        if ("first_session".equals(instance.validityStarts()) && instance.validityStart() == null) {
            LocalDate start = clock.today(tenantId);
            repo.startValidityClock(tenantId, instanceId, start, start.plusDays(instance.validityDays()));
        }

        UUID redemptionId = repo.findOldestBookedRedemption(tenantId, instanceItemId).orElse(null);
        if (redemptionId == null) {
            redemptionId = repo.insertRedemption(tenantId, instanceItemId, staffId);
        }
        repo.markRedemptionRedeemed(tenantId, redemptionId);
        repo.incrementConsumed(tenantId, instanceItemId);

        InstanceItemRow updated = repo.findInstanceItem(tenantId, instanceItemId).orElseThrow();
        repo.insertEvent(tenantId, instanceId, "session_redeemed",
                item.name() + " · balance after event " + updated.quantityConsumed() + "/" + updated.quantityTotal(),
                -1, staffId);

        if (repo.allItemsConsumed(tenantId, instanceId)) {
            repo.updateInstanceStatus(tenantId, instanceId, "completed");
        }
        messaging.redeemed(tenantId, instanceId); // COM-044: completed = no more nudges; else the next session nudge
        return detail(tenantId, instanceId);
    }

    /** The wireframe's own rule: no booking or redeeming on an expired, fully consumed, refunded or
     * cancelled package. Grace period still permits both — only past grace is a hard stop. */
    private void requireActionable(UUID tenantId, InstanceRow instance, InstanceItemRow item, LocalDate today) {
        String status = effectiveStatus(instance, today);
        if (!"active".equals(status) && !"grace".equals(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "package-not-actionable", "Package not available",
                    "This package is " + status + " and can no longer be booked or redeemed.");
        }
        if (repo.hasPendingRefund(tenantId, instance.id())) {
            throw new ApiException(HttpStatus.CONFLICT, "refund-pending", "Refund pending",
                    "A refund is awaiting approval for this package. Approve or reject it before booking or redeeming.");
        }
        if (item.quantityConsumed() >= item.quantityTotal()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "no-sessions-remaining", "No sessions remaining",
                    "Every session of " + item.name() + " has already been redeemed.");
        }
    }

    @Transactional
    public InstanceResponse extend(UUID tenantId, UUID staffId, UUID id, ExtendRequest req) {
        tenantContext.set(tenantId);
        InstanceRow instance = repo.findInstance(tenantId, id).orElseThrow(ApiException::notFound);
        LocalDate currentEnd = instance.validityEnd();
        if ("refunded".equals(instance.status()) || "cancelled".equals(instance.status())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "instance-not-extendable", "Package cannot be extended",
                    "This package is " + instance.status() + " and can no longer be extended.");
        }
        LocalDate today = clock.today(tenantId);
        LocalDate floor = currentEnd == null || currentEnd.isBefore(today) ? today : currentEnd;
        if (!req.newValidityEnd().isAfter(floor)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "extension-not-later", "Extension must be later",
                    "The new validity end must be after " + floor + ".");
        }
        repo.extendValidity(tenantId, id, req.newValidityEnd());
        messaging.extended(tenantId, id);
        repo.insertEvent(tenantId, id, "extended",
                "from " + currentEnd + " to " + req.newValidityEnd() + " · " + req.reason(), null, staffId);
        return detail(tenantId, id);
    }

    @Transactional
    public List<ExpiringSoonResponse> expiringSoon(UUID tenantId) {
        tenantContext.set(tenantId);
        LocalDate today = clock.today(tenantId);
        List<ExpiringSoonResponse> out = new ArrayList<>();
        for (InstanceRow row : repo.listActiveInstancesExpiringWithinGrace(tenantId)) {
            List<InstanceItemRow> items = repo.listInstanceItems(tenantId, row.id());
            int consumed = items.stream().mapToInt(InstanceItemRow::quantityConsumed).sum();
            int total = items.stream().mapToInt(InstanceItemRow::quantityTotal).sum();
            BigDecimal valueLeft = items.stream()
                    .map(i -> i.allocatedPrice().multiply(BigDecimal.valueOf(i.quantityTotal() - i.quantityConsumed()))
                            .divide(BigDecimal.valueOf(i.quantityTotal()), 2, RoundingMode.HALF_UP))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            int tier = currentAlertTier(row, today);
            boolean alreadySent = row.lastAlertTier() != null && row.lastAlertTier() <= tier;
            out.add(new ExpiringSoonResponse(row.id().toString(), row.patientName(), row.packageName(), consumed,
                    total, row.validityEnd(), valueLeft, tier, alreadySent, messaging.lastReminderStatus(tenantId, row.id()).orElse(null)));
        }
        return out;
    }

    /** COM-044: the desk's Send reminder really sends (the package_expiry message through the notification contract); the ledger
     * event carries the request id and its delivery status. The tier is computed server-side from the instance's own dates,
     * never trusted from the caller. A completed, refunded or cancelled package is not reminded. */
    @Transactional
    public InstanceResponse sendReminder(UUID tenantId, UUID staffId, UUID id) {
        tenantContext.set(tenantId);
        InstanceRow instance = repo.findInstance(tenantId, id).orElseThrow(ApiException::notFound);
        int tier = currentAlertTier(instance, clock.today(tenantId));
        if (tier == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "not-expiring-soon", "Not due for a reminder",
                    "This package isn't within 30 days of expiry.");
        }
        if (!List.of("active", "grace").contains(effectiveStatus(instance, clock.today(tenantId)))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "package-not-actionable", "Package not available",
                    "This package is " + effectiveStatus(instance, clock.today(tenantId)) + " and is not reminded.");
        }
        messaging.sendReminder(tenantId, staffId, instance, tier);
        return detail(tenantId, id);
    }

    static int currentAlertTier(InstanceRow row, LocalDate today) {
        if (row.validityEnd() == null) {
            return 0;
        }
        // Whole days, not Period.getDays() (only the day-of-month part: 2 months 3 days -> 3).
        long daysLeft = java.time.temporal.ChronoUnit.DAYS.between(today, row.validityEnd());
        return ALERT_TIERS.stream().filter(t -> daysLeft <= t).findFirst().orElse(0);
    }

    @Transactional
    public RefundPreviewResponse refundPreview(UUID tenantId, UUID id) {
        tenantContext.set(tenantId);
        InstanceRow instance = repo.findInstance(tenantId, id).orElseThrow(ApiException::notFound);
        String currency = currency(tenantId);
        PackageSplit.Refund r = refund(instance, repo.listInstanceItems(tenantId, id), currency);
        List<RefundPreviewItem> previewItems = r.lines().stream()
                .map(l -> new RefundPreviewItem(l.component(), l.usedQty(), amount(l.usedValueMinor(), currency))).toList();
        List<RefundPreviewLine> lines = r.lines().stream()
                .map(l -> new RefundPreviewLine(l.component(), l.usedQty(), l.usedValueMinor(), l.refundTaxableMinor(), l.refundTaxMinor())).toList();
        return new RefundPreviewResponse(amount(r.paidMinor(), currency), amount(r.usedMinor(), currency), amount(r.refundMinor(), currency),
                amount(r.owedMinor(), currency), previewItems, lines, r.refundMinor(), r.owedMinor());
    }

    /** FIN-025: the per-component refund (PackageSplit.refund) over what each component's invoice line carries. */
    private PackageSplit.Refund refund(InstanceRow instance, List<InstanceItemRow> items, String currency) {
        return PackageSplit.refund(instance.taxInclusive(), items.stream().map(i -> {
            long[] line = lineMinor(instance, i, currency);
            return new PackageSplit.Sold(i.name(), i.quantityConsumed(), Money.of(i.unitListPrice(), currency).amountMinor(), line[0], line[1]);
        }).toList());
    }

    /** {taxable, tax} of the component's invoice line; a package sold before the tax engine stored them gets them from its allocated price and rate. */
    private static long[] lineMinor(InstanceRow instance, InstanceItemRow i, String currency) {
        if (i.allocatedMinor() != null && i.taxMinor() != null) {
            return new long[]{i.allocatedMinor(), i.taxMinor()};
        }
        BigDecimal allocated = BigDecimal.valueOf(Money.of(i.allocatedPrice(), currency).amountMinor());
        long tax = (instance.taxInclusive()
                ? allocated.multiply(i.taxRatePercent()).divide(i.taxRatePercent().add(BigDecimal.valueOf(100)), 0, RoundingMode.HALF_UP)
                : allocated.multiply(i.taxRatePercent()).movePointLeft(2).setScale(0, RoundingMode.HALF_UP)).longValueExact();
        return new long[]{instance.taxInclusive() ? allocated.longValueExact() - tax : allocated.longValueExact(), tax};
    }

    private String currency(UUID tenantId) {
        return regions.region(clock.region(tenantId)).currency();
    }

    private static BigDecimal amount(long minor, String currency) {
        return new Money(minor, java.util.Currency.getInstance(currency)).amount();
    }

    /** FIN-025: the split is on when the clinic turned it on, and never for a GST clinic without the GST opinion decision id on record. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    private boolean taxSplitOn(UUID tenantId, com.nabd.hms.organization.api.RegionRules.Region region) {
        return repo.findSettings(tenantId)
                .map(s -> s.taxSplitEnabled() && (!PackageSplit.needsGstOpinion(region) || s.gstOpinionDecisionId() != null)).orElse(false);
    }

    @Transactional
    public RefundResponse requestRefund(UUID tenantId, UUID staffId, UUID id, RefundRequestRequest req) {
        tenantContext.set(tenantId);
        if (!repo.lockInstance(tenantId, id)) {
            throw ApiException.notFound();
        }
        InstanceRow instance = repo.findInstance(tenantId, id).orElseThrow(ApiException::notFound);
        if ("refunded".equals(instance.status()) || "cancelled".equals(instance.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "instance-not-refundable", "Package not refundable",
                    "This package is " + instance.status() + " and cannot be refunded.");
        }
        if (repo.hasPendingRefund(tenantId, id)) {
            throw new ApiException(HttpStatus.CONFLICT, "refund-already-open", "Refund already open",
                    "This package already has a refund awaiting approval.");
        }
        RefundPreviewResponse preview = refundPreview(tenantId, id);
        UUID refundId = repo.insertRefund(tenantId, id, req.reason(), preview.usedListValue(),
                preview.refundAmount(), preview.amountOwed(), staffId);
        audit(tenantId, staffId, "packages.refund_requested", refundId, null, repo.findRefund(tenantId, refundId).orElseThrow());
        return toRefundResponse(repo.findRefund(tenantId, refundId).orElseThrow());
    }

    @Transactional
    public List<RefundResponse> listRefunds(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.listRefunds(tenantId).stream().map(this::toRefundResponse).toList();
    }

    /** NB-160: approval closes the package, issues the credit note automatically, and (per the
     * wireframe's own footer note) reminders stop — a refunded/cancelled instance is never
     * "actionable" again, per requireActionable, and never appears in expiringSoon (status filter).
     * FIN-004: the instance row lock serialises concurrent approvers (losers see the decided status and
     * get 409 before a credit note number is drawn), and the amount is recomputed under that lock. */
    @Transactional
    public RefundResponse approveRefund(UUID tenantId, UUID staffId, UUID refundId, BigDecimal expectedAmount) {
        tenantContext.set(tenantId);
        RefundRow refund = repo.findRefund(tenantId, refundId).orElseThrow(ApiException::notFound);
        // NB-056 maker-checker: whoever requested the refund cannot also be the one who approves it.
        if (refund.requestedBy().equals(staffId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "same-user", "Self-approval blocked",
                    "A refund must be approved by someone other than whoever requested it.");
        }
        dayClose.requireTodayOpen(tenantId); // FIN-005: the credit note lands on today; day lock before the instance lock
        repo.lockInstance(tenantId, refund.instanceId());
        refund = repo.findRefund(tenantId, refundId).orElseThrow(ApiException::notFound); // re-read under the lock
        requirePending(refund);
        RefundPreviewResponse current = refundPreview(tenantId, refund.instanceId());
        BigDecimal confirmed = expectedAmount != null ? expectedAmount : refund.refundAmount();
        if (confirmed.compareTo(current.refundAmount()) != 0) {
            throw new ApiException(HttpStatus.CONFLICT, "amount-changed", "Refund amount changed",
                    "Sessions were used since the request. Review the new amount and approve again.",
                    java.util.Map.of("requestedAmount", refund.refundAmount(), "currentAmount", current.refundAmount(),
                            "currentAmountOwed", current.amountOwed()));
        }
        var creditNote = series.allocate(com.nabd.hms.organization.api.SeriesAllocator.Kind.CREDIT_NOTE); // FIN-006: own gapless series
        String creditNoteNumber = creditNote.formatted();
        if (repo.approveRefund(tenantId, refundId, staffId, creditNoteNumber, current.refundAmount()) != 1) {
            throw alreadyDecided();
        }
        issueCreditNote(tenantId, staffId, refund.instanceId(), creditNote);
        repo.updateInstanceStatus(tenantId, refund.instanceId(), "refunded");
        repo.insertEvent(tenantId, refund.instanceId(), "refunded",
                "credit note " + creditNoteNumber + " · " + current.refundAmount(), null, staffId);
        messaging.stop(tenantId, refund.instanceId()); // COM-044: no nudges after a refund
        RefundResponse approved = toRefundResponse(repo.findRefund(tenantId, refundId).orElseThrow());
        audit(tenantId, staffId, "packages.refund_approved", refundId, refund, approved);
        eventPublisher.publish(tenantId, PackageRefundApprovedHandler.EVENT_TYPE, java.util.Map.of(
                "tenantId", tenantId, "refundId", refundId, "instanceId", refund.instanceId(),
                "refundMinor", minor(current.refundAmount()), "owedMinor", minor(current.amountOwed()),
                "creditNoteNumber", creditNoteNumber));
        return approved;
    }

    /** FIN-025: the FIN-013 credit note against the package invoice, one line per component with the tax it reverses. A package sold
     * before invoice lines were linked (no invoice_line_id) keeps the number only, as before. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    private void issueCreditNote(UUID tenantId, UUID staffId, UUID instanceId, com.nabd.hms.organization.api.SeriesAllocator.Allocation number) {
        InstanceRow instance = repo.findInstance(tenantId, instanceId).orElseThrow(ApiException::notFound);
        List<InstanceItemRow> items = repo.listInstanceItems(tenantId, instanceId);
        PackageSplit.Refund r = refund(instance, items, currency(tenantId)); // same figures as the approved preview: the instance is locked
        if (r.refundMinor() < 1 || items.stream().anyMatch(i -> i.invoiceLineId() == null)) {
            return;
        }
        List<PackageInvoicing.CreditLine> lines = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            lines.add(new PackageInvoicing.CreditLine(items.get(i).invoiceLineId(), r.lines().get(i).refundTaxableMinor(), r.lines().get(i).refundTaxMinor()));
        }
        billing.creditNote(tenantId, staffId, instance.invoiceId(), number.seriesId(), number.formatted(), lines);
    }

    @Transactional
    public RefundResponse rejectRefund(UUID tenantId, UUID staffId, UUID refundId, String reason) {
        tenantContext.set(tenantId);
        RefundRow refund = repo.findRefund(tenantId, refundId).orElseThrow(ApiException::notFound);
        repo.lockInstance(tenantId, refund.instanceId());
        if (repo.rejectRefund(tenantId, refundId, staffId, reason) != 1) {
            throw alreadyDecided();
        }
        RefundResponse rejected = toRefundResponse(repo.findRefund(tenantId, refundId).orElseThrow());
        audit(tenantId, staffId, "packages.refund_rejected", refundId, refund, rejected);
        return rejected;
    }

    private void requirePending(RefundRow refund) {
        if (!"pending".equals(refund.status())) {
            throw alreadyDecided();
        }
    }

    private ApiException alreadyDecided() {
        return new ApiException(HttpStatus.CONFLICT, "already-decided", "Already decided",
                "This refund has already been approved or rejected.");
    }

    private static long minor(BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private void audit(UUID tenantId, UUID callerStaffId, String action, UUID entityId, Object before, Object after) {
        ActorDirectory.Actor actor = actors.findActor(tenantId, callerStaffId).orElseThrow(ApiException::notFound);
        auditService.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), null,
                action, "package_refund", entityId, before, after);
    }

    @Transactional
    public LiabilityResponse liability(UUID tenantId) {
        tenantContext.set(tenantId);
        LiabilityRow row = repo.computeLiability(tenantId);
        return new LiabilityResponse(row.activePackages(), row.sessionsOwed(), row.remainingListValue(),
                row.remainingAllocatedValue(), repo.countInGracePeriod(tenantId), repo.countExpiringIn30Days(tenantId),
                repo.potentialExpiryLoss(tenantId), repo.countPendingRefunds(tenantId));
    }

    static String effectiveStatus(InstanceRow row, LocalDate today) {
        if (!"active".equals(row.status())) {
            return row.status();
        }
        if (row.validityEnd() == null) {
            return "active"; // validity_starts = first_session, not yet redeemed once
        }
        if (!today.isAfter(row.validityEnd())) {
            return "active";
        }
        if (!today.isAfter(row.validityEnd().plusDays(row.graceDays()))) {
            return "grace";
        }
        return "expired";
    }

    private InstanceResponse toResponse(UUID tenantId, InstanceRow row, boolean withEvents) {
        String currency = currency(tenantId);
        List<InstanceItemResponse> items = repo.listInstanceItems(tenantId, row.id()).stream()
                .map(i -> {
                    long[] line = lineMinor(row, i, currency);
                    return new InstanceItemResponse(i.id().toString(), i.itemType(), i.name(), i.quantityTotal(),
                            i.quantityConsumed(), i.unitListPrice(), i.allocatedPrice(), i.taxRatePercent(), line[0], line[1],
                            i.taxCategory() != null ? i.taxCategory() : line[1] > 0 ? "TAXABLE" : "NIL");
                })
                .toList();
        List<InstanceEventResponse> events = withEvents
                ? repo.listEvents(tenantId, row.id()).stream()
                        .map(e -> new InstanceEventResponse(e.eventType(), e.note(), e.delta(), e.actorName(), e.createdAt(), e.deliveryStatus()))
                        .toList()
                : null;
        return new InstanceResponse(row.id().toString(), row.packageId().toString(), row.packageName(),
                row.patientId().toString(), row.patientName(), row.invoiceId().toString(), row.invoiceNumber(),
                row.soldPrice(), row.soldTax(), row.validityStart(), row.validityEnd(), row.graceDays(),
                effectiveStatus(row, clock.today(tenantId)), items, events);
    }

    private RefundResponse toRefundResponse(RefundRow r) {
        return new RefundResponse(r.id().toString(), r.instanceId().toString(), r.patientName(), r.packageName(),
                r.reason(), r.usedListValue(), r.refundAmount(), r.amountOwed(), r.status(), r.creditNoteNumber(),
                r.createdAt(), r.recomputedAmount());
    }
}
