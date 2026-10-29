package com.nabd.hms.billing.document;

import com.nabd.hms.billing.CheckoutService;
import com.nabd.hms.billing.dto.InvoiceResponse;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.iam.api.ActorDirectory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * FIN-012: renders an invoice or receipt once per invoice version and layout, stores it, and returns the stored bytes on every
 * later call (so a reprint after a price change shows the original issue). Invoice version = invoices.version (moves with every
 * payment or totals change); receipt version = number of payments.
 */
@Service
public class InvoiceDocumentService {

    private static final Set<String> KINDS = Set.of("invoice", "receipt");

    private final CheckoutService checkout;
    private final InvoiceDocumentRepository repo;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final com.nabd.hms.common.ClinicClock clock;
    private final com.nabd.hms.common.TenantContext tenantContext;
    private final com.nabd.hms.pharmacy.api.PharmacyTaxInputs pharmacy;

    InvoiceDocumentService(CheckoutService checkout, InvoiceDocumentRepository repo, AuditService audit, ActorDirectory actors,
                           com.nabd.hms.common.ClinicClock clock, com.nabd.hms.common.TenantContext tenantContext,
                           com.nabd.hms.pharmacy.api.PharmacyTaxInputs pharmacy) {
        this.pharmacy = pharmacy;
        this.clock = clock;
        this.tenantContext = tenantContext;
        this.checkout = checkout;
        this.repo = repo;
        this.audit = audit;
        this.actors = actors;
    }

    @Transactional
    public byte[] document(UUID invoiceId, String layout, String kind, UUID staffId) {
        UUID tenantId = tenantContext.current();
        if (!KINDS.contains(kind) || !("a4".equals(layout) || "thermal80".equals(layout))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "layout must be a4 or thermal80 and kind invoice or receipt.");
        }
        InvoiceResponse inv = checkout.getInvoice(tenantId, invoiceId); // 404 for an unknown or foreign invoice
        boolean receipt = "receipt".equals(kind);
        if (receipt && inv.payments().isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "no-payment", "No payment yet", "A receipt needs at least one payment.");
        }
        int version = receipt ? inv.payments().size() : repo.invoiceVersion(tenantId, invoiceId);
        var stored = repo.find(tenantId, invoiceId, kind, layout, version);
        if (stored.isPresent()) {
            return stored.get();
        }
        boolean thermal = "thermal80".equals(layout);
        // PHL-021: a pharmacy invoice (a line of a stocked item) carries the drug licence numbers in the header and the zero-rating reason on its lines
        var heads = repo.catalogueIds(tenantId, invoiceId);
        var pharmacyLines = pharmacy.inputs(heads.stream().filter(java.util.Objects::nonNull).distinct().toList());
        var supplier = repo.supplier(tenantId, clock.zone(tenantId));
        if (!pharmacyLines.isEmpty()) {
            supplier = new InvoiceDocumentText.Supplier(supplier.legalName(), supplier.gstin(), supplier.stateCode(), supplier.zone(), pharmacy.licenceLines());
        }
        List<String> notes = heads.stream().map(h -> h != null && pharmacyLines.containsKey(h) && pharmacyLines.get(h).zeroRated()
                ? com.nabd.hms.billing.tax.PharmacyTaxLines.ZERO_RATED_NOTE : null).toList();
        var lines = InvoiceDocumentText.build(inv, supplier, kind, receipt ? repo.latestReceiptNumber(tenantId, invoiceId) : null,
                thermal ? 48 : 90, repo.sacHsn(tenantId, invoiceId), notes);
        byte[] pdf = thermal ? SimplePdf.render(lines, 226.8f, 0, 12f, 7f, true) : SimplePdf.render(lines, 595.3f, 841.9f, 40f, 9f, false);
        String key = "tenants/" + tenantId + "/billing/" + invoiceId + "/" + kind + "-" + layout + "-v" + version + ".pdf";
        if (repo.store(tenantId, invoiceId, kind, layout, version, key, sha256(pdf), pdf)) {
            String[] actor = actors.findActor(tenantId, staffId).map(a -> new String[]{a.name(), a.role()}).orElse(new String[]{"Unknown", "Unknown"});
            audit.record(tenantId, "staff", staffId, actor[0], actor[1], null, "billing.document_rendered", "invoice", invoiceId, null,
                    Map.of("kind", kind, "layout", layout, "version", version));
            return pdf;
        }
        return repo.find(tenantId, invoiceId, kind, layout, version).orElse(pdf);
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
