package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.pharmacy.api.StockCommands;
import com.nabd.hms.pharmacy.dto.GoodsReceiptRequest;
import com.nabd.hms.pharmacy.dto.GoodsReceiptResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PHL-007: posts a supplier bill. One transaction: the receipt, its lines and one 'receipt' stock movement per line (paid + free
 * units, through {@link StockCommands#receive}); any refused line rolls the whole bill back. Duplicate (supplier, bill number)
 * is 409; an expiry before the clinic's today is 422 batch-expired.
 */
@Service
public class GoodsReceiptService {

    private final GoodsReceiptRepository repo;
    private final StockCommands stock;
    private final TenantContext tenant;
    private final ClinicClock clock;
    private final AuditService audit;
    private final ActorDirectory actors;

    GoodsReceiptService(GoodsReceiptRepository repo, StockCommands stock, TenantContext tenant, ClinicClock clock, AuditService audit,
                        ActorDirectory actors) {
        this.repo = repo;
        this.stock = stock;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
        this.actors = actors;
    }

    /** Effective cost of one unit when part of the quantity is free: rate x paid / (paid + free), half-up to 2 places. */
    static BigDecimal effectiveRate(BigDecimal rate, int qty, int freeQty) {
        return rate.multiply(BigDecimal.valueOf(qty)).divide(BigDecimal.valueOf((long) qty + freeQty), 2, RoundingMode.HALF_UP);
    }

    @Transactional
    public GoodsReceiptResponse post(UUID staffId, String ip, GoodsReceiptRequest req) {
        UUID tenantId = tenant.current();
        LocalDate today = clock.today(tenantId);
        String name = req.supplierName().trim();
        String bill = req.billNumber().trim();
        if (name.isEmpty() || bill.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Supplier and bill number are required.");
        }
        for (int i = 0; i < req.lines().size(); i++) {
            if (req.lines().get(i).expiryDate().isBefore(today)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "batch-expired", "Batch already expired",
                        "Line " + (i + 1) + " has an expiry date in the past; expired stock cannot be received.", Map.of("line", i + 1));
            }
        }
        String key = req.supplierId() != null ? req.supplierId().toString() : name.toLowerCase(java.util.Locale.ROOT);
        if (repo.billExists(tenantId, key, bill)) {
            throw duplicate();
        }
        BigDecimal subtotal = BigDecimal.ZERO;
        for (var l : req.lines()) {
            subtotal = subtotal.add(l.rate().multiply(BigDecimal.valueOf(l.qty())).setScale(2, RoundingMode.HALF_UP));
        }
        UUID receiptId;
        try {
            receiptId = repo.insertReceipt(tenantId, req.supplierId(), name, key, bill, req.billDate(), subtotal, req.lines().size(), staffId);
        } catch (DuplicateKeyException e) { // a concurrent post of the same bill won the unique index
            throw duplicate();
        }
        List<GoodsReceiptResponse.Batch> batches = new ArrayList<>();
        int order = 0;
        for (var l : req.lines()) {
            int free = l.freeQtyOrZero();
            BigDecimal eff = effectiveRate(l.rate(), l.qty(), free);
            var res = stock.receive(l.itemId(), l.qty() + free, staffId, "Supplier bill " + bill); // 404s an unknown item before the line's FK is hit
            repo.insertLine(tenantId, receiptId, order++, l.itemId(), l.batchNo().trim(), l.expiryDate(), l.mrp(), l.rate(), l.qty(), free,
                    l.taxRateOrZero(), eff, l.rate().multiply(BigDecimal.valueOf(l.qty())).setScale(2, RoundingMode.HALF_UP));
            batches.add(new GoodsReceiptResponse.Batch(l.itemId(), null, l.batchNo().trim(), l.expiryDate(), l.mrp(), eff, l.qty() + free, res.qtyAfter()));
        }
        String[] actor = actors.findActor(tenantId, staffId).map(a -> new String[]{a.name(), a.role()}).orElse(new String[]{"Unknown", "Unknown"});
        audit.record(tenantId, "staff", staffId, actor[0], actor[1], ip, "pharmacy.goods_receipt_posted", "goods_receipt", receiptId, null,
                Map.of("lineCount", req.lines().size(), "subtotal", subtotal));
        return new GoodsReceiptResponse(receiptId, name, bill, req.billDate(), subtotal, batches);
    }

    private static ApiException duplicate() {
        return new ApiException(HttpStatus.CONFLICT, "duplicate-supplier-bill", "Bill already posted",
                "This supplier bill number was already posted.");
    }
}
