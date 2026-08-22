package com.nabd.hms.packages;

import com.nabd.hms.billing.CheckoutService;
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
import com.nabd.hms.packages.dto.RefundPreviewResponse;
import com.nabd.hms.packages.dto.RefundRequestRequest;
import com.nabd.hms.packages.dto.RefundResponse;
import com.nabd.hms.packages.dto.SellPackageRequest;
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
 * with the xlsx's Delivery Phase column): scheduled instalments (NB-156, needs a payment gateway
 * that doesn't exist anywhere in this codebase) and transfer/gifting (NB-159). Expiry alerts and
 * next-session nudges are real here as a computed list + a manual "mark sent" action — the automatic
 * WhatsApp push itself needs NB-197's messaging infra, which doesn't exist yet either.
 */
@Service
public class PackageInstanceService {

    // ascending, so the first match is the tightest (most urgent) applicable tier
    private static final List<Integer> ALERT_TIERS = List.of(7, 14, 30);

    private final PackageRepository repo;
    private final TenantContext tenantContext;
    private final CheckoutService checkoutService;
    private final AuditService auditService;

    PackageInstanceService(PackageRepository repo, TenantContext tenantContext, CheckoutService checkoutService,
                            AuditService auditService) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.checkoutService = checkoutService;
        this.auditService = auditService;
    }

    @Transactional
    public InstanceResponse sell(UUID tenantId, UUID staffId, SellPackageRequest req) {
        tenantContext.set(tenantId);
        PackageRow pkg = repo.findPackage(tenantId, req.packageId()).orElseThrow(this::notFound);
        if (!"on_sale".equals(pkg.status())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "package-not-on-sale", "Package not on sale",
                    "This package isn't on sale right now.");
        }
        if (repo.hasActiveInstance(tenantId, req.patientId(), req.packageId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "duplicate-active-package", "Conflicting active package",
                    "This patient already has an active copy of this package.");
        }
        List<PackageItemRow> items = repo.listItems(tenantId, req.packageId());
        BigDecimal listTotal = items.stream().map(i -> i.unitListPrice().multiply(BigDecimal.valueOf(i.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        record Allocated(PackageItemRow item, UUID id, BigDecimal allocatedPrice, BigDecimal exTaxUnitPrice) {
        }
        List<Allocated> allocations = new ArrayList<>();
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
            allocations.add(new Allocated(item, UUID.randomUUID(), allocated, exTaxUnitPrice));
        }

        List<LineItemRequest> lineItems = allocations.stream()
                .map(a -> new LineItemRequest("PKGI-" + a.id().toString().substring(0, 8).toUpperCase(),
                        a.item().name(), "Package", a.item().quantity(), a.exTaxUnitPrice(), a.item().taxRatePercent()))
                .toList();
        InvoiceResponse invoice = checkoutService.sellPackageInvoice(tenantId, staffId, req.patientId(),
                new CheckoutRequest(lineItems, BigDecimal.ZERO));
        checkoutService.recordPayment(tenantId, invoice.id(), staffId, new PaymentRequest(req.paymentMethod(), invoice.total()));

        LocalDate today = LocalDate.now();
        boolean startsNow = "purchase_date".equals(pkg.validityStarts());
        LocalDate validityStart = startsNow ? today : null;
        LocalDate validityEnd = startsNow ? today.plusDays(pkg.validityDays()) : null;

        UUID instanceId = UUID.randomUUID();
        repo.insertInstance(instanceId, tenantId, pkg.id(), req.patientId(), invoice.id(), pkg.name(),
                pkg.price(), invoice.tax(), pkg.validityStarts(), pkg.validityDays(), validityStart, validityEnd,
                pkg.graceDays(), staffId);
        for (Allocated a : allocations) {
            repo.insertInstanceItem(a.id(), tenantId, instanceId, a.item().itemType(), a.item().name(),
                    a.item().quantity(), a.item().unitListPrice(), a.allocatedPrice(), a.item().taxRatePercent());
        }
        repo.insertEvent(tenantId, instanceId, "sold", pkg.name() + " · " + pkg.price(), null, staffId);
        repo.insertEvent(tenantId, instanceId, "payment_received", req.paymentMethod(), null, staffId);
        repo.insertEvent(tenantId, instanceId, "invoice_issued", "GST invoice · " + invoice.invoiceNumber(), null, staffId);

        return detail(tenantId, instanceId);
    }

    @Transactional
    public List<InstanceResponse> list(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.listInstances(tenantId).stream().map(row -> toResponse(tenantId, row, false)).toList();
    }

    @Transactional
    public InstanceResponse detail(UUID tenantId, UUID id) {
        tenantContext.set(tenantId);
        InstanceRow row = repo.findInstance(tenantId, id).orElseThrow(this::notFound);
        return toResponse(tenantId, row, true);
    }

    @Transactional
    public InstanceResponse book(UUID tenantId, UUID staffId, UUID instanceItemId) {
        tenantContext.set(tenantId);
        InstanceItemRow item = repo.findInstanceItem(tenantId, instanceItemId).orElseThrow(this::notFound);
        UUID instanceId = repo.findInstanceIdForItem(tenantId, instanceItemId).orElseThrow(this::notFound);
        InstanceRow instance = repo.findInstance(tenantId, instanceId).orElseThrow(this::notFound);
        requireActionable(instance, item);

        repo.insertRedemption(tenantId, instanceItemId, staffId);
        repo.insertEvent(tenantId, instanceId, "session_booked", item.name() + " · no entitlement consumed on booking", 0, staffId);
        return detail(tenantId, instanceId);
    }

    @Transactional
    public InstanceResponse redeem(UUID tenantId, UUID staffId, UUID instanceItemId) {
        tenantContext.set(tenantId);
        InstanceItemRow item = repo.findInstanceItem(tenantId, instanceItemId).orElseThrow(this::notFound);
        UUID instanceId = repo.findInstanceIdForItem(tenantId, instanceItemId).orElseThrow(this::notFound);
        InstanceRow instance = repo.findInstance(tenantId, instanceId).orElseThrow(this::notFound);
        requireActionable(instance, item);

        if ("first_session".equals(instance.validityStarts()) && instance.validityStart() == null) {
            LocalDate start = LocalDate.now();
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
        return detail(tenantId, instanceId);
    }

    /** The wireframe's own rule: no booking or redeeming on an expired, fully consumed, refunded or
     * cancelled package. Grace period still permits both — only past grace is a hard stop. */
    private void requireActionable(InstanceRow instance, InstanceItemRow item) {
        String status = effectiveStatus(instance);
        if (!"active".equals(status) && !"grace".equals(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "package-not-actionable", "Package not available",
                    "This package is " + status + " and can no longer be booked or redeemed.");
        }
        if (item.quantityConsumed() >= item.quantityTotal()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "no-sessions-remaining", "No sessions remaining",
                    "Every session of " + item.name() + " has already been redeemed.");
        }
    }

    @Transactional
    public InstanceResponse extend(UUID tenantId, UUID staffId, UUID id, ExtendRequest req) {
        tenantContext.set(tenantId);
        InstanceRow instance = repo.findInstance(tenantId, id).orElseThrow(this::notFound);
        LocalDate currentEnd = instance.validityEnd();
        repo.extendValidity(tenantId, id, req.newValidityEnd());
        repo.insertEvent(tenantId, id, "extended",
                "from " + currentEnd + " to " + req.newValidityEnd() + " · " + req.reason(), null, staffId);
        return detail(tenantId, id);
    }

    @Transactional
    public List<ExpiringSoonResponse> expiringSoon(UUID tenantId) {
        tenantContext.set(tenantId);
        List<ExpiringSoonResponse> out = new ArrayList<>();
        for (InstanceRow row : repo.listActiveInstancesExpiringWithinGrace(tenantId)) {
            List<InstanceItemRow> items = repo.listInstanceItems(tenantId, row.id());
            int consumed = items.stream().mapToInt(InstanceItemRow::quantityConsumed).sum();
            int total = items.stream().mapToInt(InstanceItemRow::quantityTotal).sum();
            BigDecimal valueLeft = items.stream()
                    .map(i -> i.allocatedPrice().multiply(BigDecimal.valueOf(i.quantityTotal() - i.quantityConsumed()))
                            .divide(BigDecimal.valueOf(i.quantityTotal()), 2, RoundingMode.HALF_UP))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            int tier = currentAlertTier(row);
            boolean alreadySent = row.lastAlertTier() != null && row.lastAlertTier() <= tier;
            out.add(new ExpiringSoonResponse(row.id().toString(), row.patientName(), row.packageName(), consumed,
                    total, row.validityEnd(), valueLeft, tier, alreadySent));
        }
        return out;
    }

    /** No WhatsApp/notification channel exists yet (NB-197) — this records that a reminder went out
     * (for the ledger and the "already sent" dedupe) rather than actually dispatching one. The tier
     * is computed server-side from the instance's own dates, never trusted from the caller. */
    @Transactional
    public InstanceResponse sendReminder(UUID tenantId, UUID staffId, UUID id) {
        tenantContext.set(tenantId);
        InstanceRow instance = repo.findInstance(tenantId, id).orElseThrow(this::notFound);
        int tier = currentAlertTier(instance);
        if (tier == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "not-expiring-soon", "Not due for a reminder",
                    "This package isn't within 30 days of expiry.");
        }
        repo.updateLastAlertTier(tenantId, id, tier);
        repo.insertEvent(tenantId, id, "expiry_warning_sent", tier + "-day template", null, staffId);
        return detail(tenantId, id);
    }

    private int currentAlertTier(InstanceRow row) {
        if (row.validityEnd() == null) {
            return 0;
        }
        int daysLeft = (int) LocalDate.now().until(row.validityEnd()).getDays();
        return ALERT_TIERS.stream().filter(t -> daysLeft <= t).findFirst().orElse(0);
    }

    @Transactional
    public RefundPreviewResponse refundPreview(UUID tenantId, UUID id) {
        tenantContext.set(tenantId);
        InstanceRow instance = repo.findInstance(tenantId, id).orElseThrow(this::notFound);
        List<InstanceItemRow> items = repo.listInstanceItems(tenantId, id);
        BigDecimal usedListValue = items.stream()
                .map(i -> i.unitListPrice().multiply(BigDecimal.valueOf(i.quantityConsumed())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paid = instance.soldPrice().add(instance.soldTax());
        BigDecimal diff = paid.subtract(usedListValue);
        BigDecimal refundAmount = diff.max(BigDecimal.ZERO);
        BigDecimal owed = diff.signum() < 0 ? diff.negate() : BigDecimal.ZERO;
        List<RefundPreviewItem> previewItems = items.stream()
                .map(i -> new RefundPreviewItem(i.name(), i.quantityConsumed(),
                        i.unitListPrice().multiply(BigDecimal.valueOf(i.quantityConsumed()))))
                .toList();
        return new RefundPreviewResponse(paid, usedListValue, refundAmount, owed, previewItems);
    }

    @Transactional
    public RefundResponse requestRefund(UUID tenantId, UUID staffId, UUID id, RefundRequestRequest req) {
        tenantContext.set(tenantId);
        repo.findInstance(tenantId, id).orElseThrow(this::notFound);
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
     * "actionable" again, per requireActionable, and never appears in expiringSoon (status filter). */
    @Transactional
    public RefundResponse approveRefund(UUID tenantId, UUID staffId, UUID refundId) {
        tenantContext.set(tenantId);
        RefundRow refund = repo.findRefund(tenantId, refundId).orElseThrow(this::notFound);
        if ("approved".equals(refund.status())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "refund-already-approved", "Already approved",
                    "This refund has already been approved.");
        }
        // NB-056 maker-checker: whoever requested the refund cannot also be the one who approves it.
        if (refund.requestedBy().equals(staffId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "self-approval-blocked", "Self-approval blocked",
                    "A refund must be approved by someone other than whoever requested it.");
        }
        String creditNoteNumber = repo.nextCreditNoteNumber(tenantId);
        repo.approveRefund(tenantId, refundId, staffId, creditNoteNumber);
        repo.updateInstanceStatus(tenantId, refund.instanceId(), "refunded");
        repo.insertEvent(tenantId, refund.instanceId(), "refunded",
                "credit note " + creditNoteNumber + " · " + refund.refundAmount(), null, staffId);
        RefundResponse approved = toRefundResponse(repo.findRefund(tenantId, refundId).orElseThrow());
        audit(tenantId, staffId, "packages.refund_approved", refundId, refund, approved);
        return approved;
    }

    private void audit(UUID tenantId, UUID callerStaffId, String action, UUID entityId, Object before, Object after) {
        PackageModels.ActorInfo actor = repo.findActorInfo(tenantId, callerStaffId).orElseThrow(this::notFound);
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

    private String effectiveStatus(InstanceRow row) {
        if (!"active".equals(row.status())) {
            return row.status();
        }
        if (row.validityEnd() == null) {
            return "active"; // validity_starts = first_session, not yet redeemed once
        }
        LocalDate today = LocalDate.now();
        if (!today.isAfter(row.validityEnd())) {
            return "active";
        }
        if (!today.isAfter(row.validityEnd().plusDays(row.graceDays()))) {
            return "grace";
        }
        return "expired";
    }

    private InstanceResponse toResponse(UUID tenantId, InstanceRow row, boolean withEvents) {
        List<InstanceItemResponse> items = repo.listInstanceItems(tenantId, row.id()).stream()
                .map(i -> new InstanceItemResponse(i.id().toString(), i.itemType(), i.name(), i.quantityTotal(),
                        i.quantityConsumed(), i.unitListPrice(), i.allocatedPrice(), i.taxRatePercent()))
                .toList();
        List<InstanceEventResponse> events = withEvents
                ? repo.listEvents(tenantId, row.id()).stream()
                        .map(e -> new InstanceEventResponse(e.eventType(), e.note(), e.delta(), e.actorName(), e.createdAt()))
                        .toList()
                : null;
        return new InstanceResponse(row.id().toString(), row.packageId().toString(), row.packageName(),
                row.patientId().toString(), row.patientName(), row.invoiceId().toString(), row.invoiceNumber(),
                row.soldPrice(), row.soldTax(), row.validityStart(), row.validityEnd(), row.graceDays(),
                effectiveStatus(row), items, events);
    }

    private RefundResponse toRefundResponse(RefundRow r) {
        return new RefundResponse(r.id().toString(), r.instanceId().toString(), r.patientName(), r.packageName(),
                r.reason(), r.usedListValue(), r.refundAmount(), r.amountOwed(), r.status(), r.creditNoteNumber(),
                r.createdAt());
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }
}
