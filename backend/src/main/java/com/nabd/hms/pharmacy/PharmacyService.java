package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.pharmacy.dto.DispensingQueueEntryResponse;
import com.nabd.hms.pharmacy.dto.PharmacyItemResponse;
import com.nabd.hms.pharmacy.dto.PharmacyItemStatusRequest;
import com.nabd.hms.pharmacy.dto.PharmacyStockAdjustmentRequest;
import com.nabd.hms.pharmacy.dto.PharmacyStockMovementResponse;
import com.nabd.hms.pharmacy.dto.PharmacyItemWriteRequest;
import com.nabd.hms.pharmacy.dto.PharmacySettingsResponse;
import com.nabd.hms.pharmacy.dto.PharmacySettingsWriteRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nabd.hms.pharmacy.PharmacyModels.ActorInfo;
import static com.nabd.hms.pharmacy.PharmacyModels.DispensingQueueRow;
import static com.nabd.hms.pharmacy.PharmacyModels.PharmacyItemRow;

/**
 * E16 Pharmacy, scoped to the wireframe's own "Phase 2" deliverable: Hybrid mode only (one-tap
 * dispense, simple stock). In-house (batch & expiry, controlled-drug register, FEFO) is labeled
 * "Phase 3" in the wireframe itself — deliberately not built; item writes are rejected outside
 * Hybrid mode with an honest message rather than a fake in-house feature set.
 */
@Service
public class PharmacyService {

    private final PharmacyRepository repo;
    private final TenantContext tenantContext;
    private final AuditService auditService;

    PharmacyService(PharmacyRepository repo, TenantContext tenantContext, AuditService auditService) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.auditService = auditService;
    }

    @Transactional
    public PharmacySettingsResponse getSettings(UUID tenantId) {
        tenantContext.set(tenantId);
        return new PharmacySettingsResponse(repo.findMode(tenantId).orElse("external"));
    }

    /** PHA-006: a real mode change needs a reason and is audited; re-sending the current mode is a no-op.
     * In-house is Phase 3 (not built), so it can't be switched to — a tenant already on it keeps it. */
    @Transactional
    public PharmacySettingsResponse updateSettings(UUID tenantId, UUID callerStaffId, String ipAddress,
                                                   PharmacySettingsWriteRequest req) {
        tenantContext.set(tenantId);
        String current = repo.findMode(tenantId).orElse("external");
        if (req.mode() == null || req.mode().equals(current)) {
            return new PharmacySettingsResponse(current);
        }
        if ("in_house".equals(req.mode())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "mode-not-available", "Mode not available",
                    "In-house pharmacy (batch & expiry tracking) isn't available yet. Choose Hybrid or External.");
        }
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (reason.length() < 3 || reason.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "reason-required", "Reason required",
                    "Give a reason (3-200 characters) for changing the pharmacy mode.");
        }
        repo.upsertMode(tenantId, req.mode());
        ActorInfo actor = repo.findActorInfo(tenantId, callerStaffId).orElseThrow(this::notFound);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("mode", req.mode());
        after.put("reason", reason);
        auditService.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), ipAddress,
                "pharmacy.mode_changed", "pharmacy_settings", tenantId, Map.of("mode", current), after);
        return new PharmacySettingsResponse(req.mode());
    }

    @Transactional
    public List<PharmacyItemResponse> listItems(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.listItems(tenantId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public PharmacyItemResponse addItem(UUID tenantId, UUID callerStaffId, String ipAddress, PharmacyItemWriteRequest req) {
        tenantContext.set(tenantId);
        requireHybridMode(tenantId);
        String code = "PHM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID id = repo.insertItem(tenantId, code, req);
        int opening = req.stockQtyOrZero();
        if (opening > 0) {
            // Opening stock is a stock change like any other: it gets a movement row and an audit event.
            recordAdjustment(tenantId, callerStaffId, ipAddress, id, 0, opening, "Opening stock");
        }
        return toResponse(repo.findItem(tenantId, id).orElseThrow(this::notFound));
    }

    /** Edits name, price, tax, Rx flag and HSN. A stockQty in the body is ignored on purpose (V2-004): stock only
     * changes through {@link #adjustStock}, so an edit from a stale list can no longer overwrite a newer count. */
    @Transactional
    public PharmacyItemResponse updateItem(UUID tenantId, UUID id, PharmacyItemWriteRequest req) {
        tenantContext.set(tenantId);
        requireHybridMode(tenantId);
        if (repo.updateItem(tenantId, id, req) == 0) {
            throw notFound();
        }
        return toResponse(repo.findItem(tenantId, id).orElseThrow(this::notFound));
    }

    /** V2-004: relative (delta) or stock-take (setTo) change, reason mandatory. The item row is locked, the new
     * count is computed from the locked value, and an immutable movement row plus an audit event record it. */
    @Transactional
    public PharmacyItemResponse adjustStock(UUID tenantId, UUID callerStaffId, String ipAddress, UUID id,
                                             PharmacyStockAdjustmentRequest req) {
        tenantContext.set(tenantId);
        requireHybridMode(tenantId);
        if ((req.delta() == null) == (req.setTo() == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                    "Send exactly one of 'delta' (a change) or 'setTo' (a counted total).");
        }
        String reason = req.reason().trim();
        if (reason.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "reason-required", "Reason required",
                    "A reason is required for every stock change.");
        }
        PharmacyItemRow item = repo.lockItem(tenantId, id).orElseThrow(this::notFound);
        long target = req.setTo() != null ? req.setTo() : (long) item.stockQty() + req.delta();
        if (target < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "insufficient-stock", "Stock cannot go below zero",
                    "Only " + item.stockQty() + " in stock, so the count cannot be reduced by " + (-req.delta()) + ".");
        }
        if (target == item.stockQty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "no-change", "Nothing to change",
                    "The stock count is already " + item.stockQty() + ".");
        }
        recordAdjustment(tenantId, callerStaffId, ipAddress, id, item.stockQty(), (int) target, reason);
        return toResponse(repo.findItem(tenantId, id).orElseThrow(this::notFound));
    }

    /** V2-007: activate / deactivate. Idempotent; only a real change is audited. */
    @Transactional
    public PharmacyItemResponse setActive(UUID tenantId, UUID callerStaffId, String ipAddress, UUID id,
                                           PharmacyItemStatusRequest req) {
        tenantContext.set(tenantId);
        requireHybridMode(tenantId);
        PharmacyItemRow item = repo.lockItem(tenantId, id).orElseThrow(this::notFound);
        boolean active = Boolean.TRUE.equals(req.active());
        if (item.active() != active) {
            repo.setActive(tenantId, id, active);
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("active", item.active());
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("active", active);
            after.put("itemCode", item.code());
            after.put("reason", req.reason() == null || req.reason().isBlank() ? null : req.reason().trim());
            audit(tenantId, callerStaffId, ipAddress, active ? "pharmacy.item_activated" : "pharmacy.item_deactivated", id, before, after);
        }
        return toResponse(repo.findItem(tenantId, id).orElseThrow(this::notFound));
    }

    @Transactional
    public List<PharmacyStockMovementResponse> movements(UUID tenantId, UUID id) {
        tenantContext.set(tenantId);
        repo.findItem(tenantId, id).orElseThrow(this::notFound);
        return repo.listMovements(tenantId, id).stream()
                .map(m -> new PharmacyStockMovementResponse(m.id(), m.kind(), m.delta(), m.qtyBefore(), m.qtyAfter(), m.reason(),
                        m.actorId(), m.actorName(), m.occurredAt()))
                .toList();
    }

    private void recordAdjustment(UUID tenantId, UUID staffId, String ip, UUID itemId, int before, int after, String reason) {
        repo.setStock(tenantId, itemId, after);
        repo.insertMovement(tenantId, itemId, "adjustment", after - before, before, after, reason, staffId);
        Map<String, Object> beforeState = new LinkedHashMap<>();
        beforeState.put("stockQty", before);
        Map<String, Object> afterState = new LinkedHashMap<>();
        afterState.put("stockQty", after);
        afterState.put("delta", after - before);
        afterState.put("reason", reason);
        audit(tenantId, staffId, ip, "pharmacy.stock_adjusted", itemId, beforeState, afterState);
    }

    private void audit(UUID tenantId, UUID staffId, String ip, String action, UUID itemId, Object before, Object after) {
        ActorInfo actor = repo.findActorInfo(tenantId, staffId).orElseThrow(this::notFound);
        auditService.record(tenantId, "staff", staffId, actor.name(), actor.role(), ip, action, "pharmacy_item", itemId, before, after);
    }

    /** NB-179: live query, not a job — "within five seconds of consultation close" is trivially true
     * for a read that runs the instant the queue page loads. */
    @Transactional
    public List<DispensingQueueEntryResponse> dispensingQueue(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.listDispensingQueue(tenantId).stream().map(r -> toDispensingResponse(tenantId, r)).toList();
    }

    private DispensingQueueEntryResponse toDispensingResponse(UUID tenantId, DispensingQueueRow r) {
        List<DispensingQueueEntryResponse.Item> items = repo.listDispensingItems(tenantId, r.prescriptionId()).stream()
                .map(i -> new DispensingQueueEntryResponse.Item(i.drugName(), i.dosage(), i.frequency(), i.duration(), i.instructions(), i.stockQty()))
                .toList();
        return new DispensingQueueEntryResponse(r.prescriptionId(), r.queueEntryId(), r.patientId(), r.patientName(),
                r.doctorName(), r.signedAt(), r.token(), items);
    }

    private void requireHybridMode(UUID tenantId) {
        String mode = repo.findMode(tenantId).orElse("external");
        if (!"hybrid".equals(mode)) {
            String detail = "in_house".equals(mode)
                    ? "In-house pharmacy (batch & expiry tracking) isn't available yet — switch to Hybrid mode to manage a simple item list."
                    : "Switch the pharmacy mode to Hybrid in Clinic Setup before adding items.";
            throw new ApiException(HttpStatus.BAD_REQUEST, "pharmacy-mode-not-hybrid", "Hybrid mode required", detail);
        }
    }

    private PharmacyItemResponse toResponse(PharmacyItemRow r) {
        return new PharmacyItemResponse(r.id().toString(), r.code(), r.name(), r.isRx(), r.hsnCode(), r.price(),
                r.taxRatePercent(), r.stockQty(), r.active());
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested pharmacy item was not found.");
    }
}
