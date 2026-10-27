package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.pharmacy.FefoPicker.Batch;
import com.nabd.hms.pharmacy.api.StockCommands;
import com.nabd.hms.pharmacy.dto.BatchDtos.BatchMovementResponse;
import com.nabd.hms.pharmacy.dto.BatchDtos.BatchPatchRequest;
import com.nabd.hms.pharmacy.dto.BatchDtos.BatchResponse;
import com.nabd.hms.pharmacy.dto.BatchDtos.PickEntry;
import com.nabd.hms.pharmacy.dto.BatchDtos.PickLineResponse;
import com.nabd.hms.pharmacy.dto.BatchDtos.PickRequest;
import com.nabd.hms.pharmacy.dto.BatchDtos.PickResponse;
import com.nabd.hms.pharmacy.dto.BatchDtos.StockItemResponse;
import com.nabd.hms.pharmacy.dto.BatchDtos.StockPageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** PHL-005 / PHL-006: batch reads, the audited batch correction and the FEFO pick preview. Stock quantities change only in StockCommands. */
@Service
public class BatchService {

    private final BatchRepository batches;
    private final PharmacyRepository repo;
    private final StockCommands stock;
    private final TenantContext tenant;
    private final ClinicClock clock;
    private final AuditService audit;
    private final ActorDirectory actors;

    BatchService(BatchRepository batches, PharmacyRepository repo, StockCommands stock, TenantContext tenant, ClinicClock clock,
                 AuditService audit, ActorDirectory actors) {
        this.batches = batches;
        this.repo = repo;
        this.stock = stock;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
        this.actors = actors;
    }

    @Transactional
    public List<BatchResponse> list(UUID itemId, boolean includeEmpty) {
        UUID t = tenant.current();
        repo.findItem(t, itemId).orElseThrow(BatchService::itemNotFound);
        boolean hybrid = mode(t) != PharmacyMode.IN_HOUSE; // Hybrid: one implicit batch, no batch fields
        return batches.list(t, itemId, includeEmpty).stream().map(b -> hybrid ? implicit(b) : view(b)).toList();
    }

    @Transactional
    public StockPageResponse stock(String q, String scheduleClass, Integer expiringWithinDays, String cursor, int limit) {
        UUID t = tenant.current();
        String afterName = null;
        UUID afterId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] p = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\u0001", 2);
                afterName = p[0];
                afterId = UUID.fromString(p[1]);
            } catch (RuntimeException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-cursor", "Invalid cursor", "The cursor is not valid.");
            }
        }
        LocalDate by = expiringWithinDays == null ? null : clock.today(t).plusDays(expiringWithinDays);
        int n = Math.max(1, Math.min(limit, 100));
        String needle = q == null || q.isBlank() ? null : q.trim();
        List<BatchRepository.StockRow> rows = batches.stockPage(t, needle, scheduleClass == null || scheduleClass.isBlank() ? null : scheduleClass,
                by, afterName, afterId, n + 1);
        boolean more = rows.size() > n;
        List<BatchRepository.StockRow> page = more ? rows.subList(0, n) : rows;
        String next = null;
        if (more) {
            BatchRepository.StockRow last = page.get(page.size() - 1);
            next = Base64.getUrlEncoder().withoutPadding().encodeToString((last.name().toLowerCase() + "\u0001" + last.itemId()).getBytes(StandardCharsets.UTF_8));
        }
        boolean hybrid = mode(t) != PharmacyMode.IN_HOUSE;
        return new StockPageResponse(page.stream().map(r -> new StockItemResponse(r.itemId(), r.code(), r.name(), r.scheduleClass(), r.onHand(),
                hybrid ? null : r.nearestExpiry(), hybrid ? 0 : r.batchCount(), !hybrid && r.expiryUnknown(), hybrid ? null : r.value())).toList(), next);
    }

    @Transactional
    public List<BatchMovementResponse> movements(UUID batchId) {
        UUID t = tenant.current();
        batches.itemOf(t, batchId).orElseThrow(BatchService::batchNotFound);
        return batches.movements(t, batchId).stream()
                .map(m -> new BatchMovementResponse(m.id(), m.kind(), m.delta(), m.qtyBefore(), m.qtyAfter(), m.reason(), m.occurredAt())).toList();
    }

    /** Batch number, expiry and location only; quantity changes go through StockCommands. Audited with the reason. */
    @Transactional
    public BatchResponse patch(UUID callerStaffId, String ip, UUID batchId, BatchPatchRequest req) {
        UUID t = tenant.current();
        UUID itemId = batches.itemOf(t, batchId).orElseThrow(BatchService::batchNotFound);
        PharmacyModeGuard.check(mode(t), PharmacyMode.IN_HOUSE);
        repo.lockItem(t, itemId); // same lock order as StockCommands: item first, then batch
        Batch before = batches.lock(t, batchId).orElseThrow(BatchService::batchNotFound);
        String no = req.batchNo() == null || req.batchNo().isBlank() ? null : req.batchNo().trim();
        LocalDate expiry = parseExpiry(req.expiryDate());
        String location = req.location() == null || req.location().isBlank() ? null : req.location().trim();
        if (expiry != null) {
            batches.lastSaleAt(t, batchId).map(i -> i.atZone(clock.zone(t)).toLocalDate()).filter(expiry::isBefore).ifPresent(d -> {
                throw new ApiException(HttpStatus.CONFLICT, "batch-has-movements-after-date", "Batch was sold after that date",
                        "Stock from this batch was dispensed on " + d + ", after the new expiry date.");
            });
        }
        try {
            batches.patch(t, batchId, no, expiry, location);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "batch-exists", "Batch already exists", "Another batch of this item has that number and expiry.");
        }
        Batch after = batches.list(t, itemId, true).stream().filter(b -> b.id().equals(batchId)).findFirst().orElseThrow();
        Map<String, Object> was = new LinkedHashMap<>();
        was.put("batchNo", before.batchNo());
        was.put("expiryDate", before.expiryDate());
        was.put("location", before.location());
        Map<String, Object> now = new LinkedHashMap<>();
        now.put("batchNo", after.batchNo());
        now.put("expiryDate", after.expiryDate());
        now.put("location", after.location());
        now.put("reason", req.reason().trim());
        ActorDirectory.Actor actor = actors.findActor(t, callerStaffId).orElseThrow(BatchService::batchNotFound);
        audit.record(t, "staff", callerStaffId, actor.name(), actor.role(), ip, "pharmacy.batch_corrected", "stock_batch", batchId, was, now);
        return view(after);
    }

    /** Preview, no stock change. A shortfall is a 409 here (the caller asked for a plan that cannot be met). */
    @Transactional
    public PickResponse pick(PickRequest req) {
        return new PickResponse(req.lines().stream().map(l -> {
            try {
                return new PickLineResponse(l.itemId(), stock.pick(l.itemId(), l.qty(), l.courseDays()).stream()
                        .map(p -> new PickEntry(p.batchId(), p.qty(), p.expiryDate(), p.warning())).toList());
            } catch (ApiException e) {
                if ("insufficient-stock".equals(e.typeSlug())) {
                    throw new ApiException(HttpStatus.CONFLICT, "insufficient-stock", "Insufficient stock", e.getMessage());
                }
                throw e;
            }
        }).toList());
    }

    static LocalDate parseExpiry(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return s.length() == 7 ? YearMonth.parse(s).atEndOfMonth() : LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "Expiry must be a date (2027-06-30) or a month (2027-06).");
        }
    }

    private static BatchResponse view(Batch b) {
        return new BatchResponse(b.id(), b.batchNo(), b.expiryDate(), b.mrp(), b.purchaseRate(), b.qtyOnHand(), b.status(), b.location(),
                b.expiryDate() == null);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    private PharmacyMode mode(UUID t) {
        return PharmacyMode.of(repo.findMode(t).orElse("external"));
    }

    private static BatchResponse implicit(Batch b) {
        return new BatchResponse(b.id(), null, null, null, null, b.qtyOnHand(), b.status(), null, false);
    }

    private static ApiException itemNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested pharmacy item was not found.");
    }

    private static ApiException batchNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested batch was not found.");
    }
}
