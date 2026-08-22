package com.nabd.hms.billing;

import com.nabd.hms.billing.dto.CheckoutContextResponse;
import com.nabd.hms.billing.dto.CheckoutRequest;
import com.nabd.hms.billing.dto.ChargeResponse;
import com.nabd.hms.billing.dto.InvoiceResponse;
import com.nabd.hms.billing.dto.LineItemRequest;
import com.nabd.hms.billing.dto.LineItemResponse;
import com.nabd.hms.billing.dto.OtcChargesResponse;
import com.nabd.hms.billing.dto.PaymentRequest;
import com.nabd.hms.billing.dto.PaymentResponse;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
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
import java.util.UUID;

import static com.nabd.hms.billing.CheckoutModels.ChargeRow;
import static com.nabd.hms.billing.CheckoutModels.InvoiceRow;
import static com.nabd.hms.billing.CheckoutModels.LineItemInput;
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
    // ponytail: fixed 14-day window, matching the wireframe's stated follow-up rule — promote to a
    // clinic policy (alongside cancellation_window_hours etc.) if clinics ever need to vary it.
    private static final int FOLLOW_UP_WINDOW_DAYS = 14;

    private final CheckoutRepository repo;
    private final TenantContext tenantContext;
    private final QueueService queueService;

    CheckoutService(CheckoutRepository repo, TenantContext tenantContext, QueueService queueService) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.queueService = queueService;
    }

    @Transactional
    public CheckoutContextResponse getCheckoutContext(UUID tenantId, UUID queueEntryId) {
        tenantContext.set(tenantId);
        QueueEntryContext ctx = repo.findQueueEntryContext(tenantId, queueEntryId).orElseThrow(this::notFound);
        String patientName = repo.findPatientName(tenantId, ctx.patientId()).orElse("Unknown patient");
        String doctorName = repo.findStaffName(tenantId, ctx.doctorId()).orElse("Unknown doctor");
        boolean followUpEligible = repo.hasRecentCompletedVisit(tenantId, ctx.patientId(), ctx.doctorId(),
                LocalDate.now().minusDays(FOLLOW_UP_WINDOW_DAYS));
        List<ChargeResponse> charges = repo.listActiveCharges(tenantId).stream().map(this::toChargeResponse).toList();
        return new CheckoutContextResponse(queueEntryId, patientName, doctorName,
                ctx.hasAppointment() ? "Appointment" : "Walk-in", followUpEligible, currencyFor(tenantId), charges);
    }

    @Transactional
    public InvoiceResponse getExistingInvoice(UUID tenantId, UUID queueEntryId) {
        tenantContext.set(tenantId);
        InvoiceRow row = repo.findInvoiceByQueueEntry(tenantId, queueEntryId).orElseThrow(this::notFound);
        return toInvoiceResponse(tenantId, row);
    }

    @Transactional
    public InvoiceResponse checkout(UUID tenantId, UUID queueEntryId, UUID staffId, CheckoutRequest req) {
        tenantContext.set(tenantId);
        QueueEntryContext ctx = repo.findQueueEntryContext(tenantId, queueEntryId).orElseThrow(this::notFound);
        if (!"checkout_pending".equals(ctx.queueStatus())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "not-checkout-pending", "Not ready for checkout",
                    "This visit isn't at checkout yet — its status is " + ctx.queueStatus() + ".");
        }
        if (repo.findInvoiceByQueueEntry(tenantId, queueEntryId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "invoice-exists", "Invoice already exists",
                    "This visit has already been checked out.");
        }

        Totals t = computeTotals(req);
        List<LineItemInput> items = req.lineItems().stream().map(this::toLineItemInput).toList();

        UUID invoiceId = repo.insertInvoice(tenantId, queueEntryId, ctx.patientId(), ctx.doctorId(),
                t.subtotal(), t.discount(), t.tax(), t.roundOff(), t.total(), staffId);
        repo.insertLineItems(tenantId, invoiceId, items);

        // Closes the loop NB-102 left open: a doctor's "Complete consultation" moves a visit to
        // checkout_pending; billing finishing checkout is what finally moves it to completed.
        queueService.updateStatus(tenantId, staffId, queueEntryId, new QueueStatusUpdateRequest("completed"));
        log.info("invoice {} created for queue entry {} by {} (tenant {}, total {})",
                invoiceId, queueEntryId, staffId, tenantId, t.total());

        return toInvoiceResponse(tenantId, repo.findInvoice(tenantId, invoiceId).orElseThrow());
    }

    /** NB-186: a counter sale — no queue entry, no patient, no doctor. Same charge list, same
     * dispense-and-decrement-stock path (CheckoutRepository.insertLineItems), just no queue to close. */
    @Transactional
    public OtcChargesResponse getOtcCharges(UUID tenantId) {
        tenantContext.set(tenantId);
        List<ChargeResponse> charges = repo.listActiveCharges(tenantId).stream().map(this::toChargeResponse).toList();
        return new OtcChargesResponse(currencyFor(tenantId), charges);
    }

    @Transactional
    public InvoiceResponse otcCheckout(UUID tenantId, UUID staffId, CheckoutRequest req) {
        return standaloneInvoice(tenantId, staffId, null, req, "OTC");
    }

    /** E14 Treatment Packages: a package sale is also a standalone invoice — no queue entry, but
     * (unlike an OTC sale) a real patient. Reuses the same total/tax/round-off computation. */
    @Transactional
    public InvoiceResponse sellPackageInvoice(UUID tenantId, UUID staffId, UUID patientId, CheckoutRequest req) {
        return standaloneInvoice(tenantId, staffId, patientId, req, "package");
    }

    private InvoiceResponse standaloneInvoice(UUID tenantId, UUID staffId, UUID patientId, CheckoutRequest req, String kind) {
        tenantContext.set(tenantId);
        Totals t = computeTotals(req);
        List<LineItemInput> items = req.lineItems().stream().map(this::toLineItemInput).toList();

        UUID invoiceId = repo.insertInvoice(tenantId, null, patientId, null, t.subtotal(), t.discount(), t.tax(),
                t.roundOff(), t.total(), staffId);
        repo.insertLineItems(tenantId, invoiceId, items);
        log.info("{} invoice {} created by {} (tenant {}, total {})", kind, invoiceId, staffId, tenantId, t.total());

        return toInvoiceResponse(tenantId, repo.findInvoice(tenantId, invoiceId).orElseThrow());
    }

    private record Totals(BigDecimal subtotal, BigDecimal discount, BigDecimal tax, BigDecimal roundOff, BigDecimal total) {
    }

    private Totals computeTotals(CheckoutRequest req) {
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (LineItemRequest li : req.lineItems()) {
            BigDecimal lineAmount = li.unitPrice().multiply(BigDecimal.valueOf(li.quantity()));
            subtotal = subtotal.add(lineAmount);
            tax = tax.add(lineAmount.multiply(li.taxRatePercent()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
        }

        BigDecimal discount = req.discountOrZero();
        if (discount.compareTo(subtotal) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "discount-exceeds-subtotal", "Discount too large",
                    "The discount can't be more than the subtotal.");
        }

        BigDecimal rawTotal = subtotal.subtract(discount).add(tax);
        BigDecimal total = rawTotal.setScale(0, RoundingMode.HALF_UP);
        BigDecimal roundOff = total.subtract(rawTotal);
        return new Totals(subtotal, discount, tax, roundOff, total);
    }

    @Transactional
    public InvoiceResponse recordPayment(UUID tenantId, UUID invoiceId, UUID staffId, PaymentRequest req) {
        tenantContext.set(tenantId);
        InvoiceRow invoice = repo.findInvoice(tenantId, invoiceId).orElseThrow(this::notFound);
        if ("paid".equals(invoice.status())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invoice-already-paid", "Invoice already paid",
                    "This invoice has no outstanding balance.");
        }
        repo.insertPayment(tenantId, invoiceId, req.method(), req.amount(), staffId);
        BigDecimal newPaid = invoice.paid().add(req.amount());
        String newStatus = newPaid.compareTo(invoice.total()) >= 0 ? "paid" : "partial";
        repo.updateInvoicePaidStatus(tenantId, invoiceId, newPaid, newStatus);
        log.info("payment recorded on invoice {} by {}: {} {} (tenant {})", invoiceId, staffId, req.amount(), req.method(), tenantId);
        return toInvoiceResponse(tenantId, repo.findInvoice(tenantId, invoiceId).orElseThrow());
    }

    @Transactional
    public InvoiceResponse getInvoice(UUID tenantId, UUID invoiceId) {
        tenantContext.set(tenantId);
        return toInvoiceResponse(tenantId, repo.findInvoice(tenantId, invoiceId).orElseThrow(this::notFound));
    }

    private InvoiceResponse toInvoiceResponse(UUID tenantId, InvoiceRow row) {
        List<LineItemResponse> lineItems = repo.findLineItems(tenantId, row.id()).stream()
                .map(li -> new LineItemResponse(li.chargeCode(), li.chargeName(), li.category(), li.quantity(),
                        li.unitPrice(), li.taxRatePercent(), li.lineTotal()))
                .toList();
        List<PaymentResponse> payments = repo.findPayments(tenantId, row.id()).stream()
                .map(p -> new PaymentResponse(p.id(), p.method(), p.amount(), p.recordedAt()))
                .toList();
        String patientName = row.patientId() == null ? "Walk-in customer"
                : repo.findPatientName(tenantId, row.patientId()).orElse("Unknown patient");
        String doctorName = row.doctorId() == null ? "—"
                : repo.findStaffName(tenantId, row.doctorId()).orElse("Unknown doctor");
        return new InvoiceResponse(row.id(), row.invoiceNumber(), row.queueEntryId(), row.patientId(), patientName,
                row.doctorId(), doctorName, currencyFor(tenantId), row.subtotal(), row.discount(), row.tax(),
                row.roundOff(), row.total(), row.paid(), row.total().subtract(row.paid()), row.status(),
                lineItems, payments, row.createdAt());
    }

    private ChargeResponse toChargeResponse(ChargeRow c) {
        return new ChargeResponse(c.id(), c.code(), c.name(), c.category(), c.baseAmount(), c.followUpAmount(), c.taxRatePercent());
    }

    private LineItemInput toLineItemInput(LineItemRequest li) {
        return new LineItemInput(li.chargeCode(), li.chargeName(), li.category(), li.quantity(), li.unitPrice(), li.taxRatePercent());
    }

    private String currencyFor(UUID tenantId) {
        String region = repo.findTenantRegion(tenantId).orElse("IN");
        return "KSA".equals(region) ? "SAR" : "INR";
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }
}
