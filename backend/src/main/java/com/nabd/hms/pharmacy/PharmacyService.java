package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.pharmacy.dto.DispensingQueueEntryResponse;
import com.nabd.hms.pharmacy.dto.PharmacyItemResponse;
import com.nabd.hms.pharmacy.dto.PharmacyItemWriteRequest;
import com.nabd.hms.pharmacy.dto.PharmacySettingsResponse;
import com.nabd.hms.pharmacy.dto.PharmacySettingsWriteRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

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

    PharmacyService(PharmacyRepository repo, TenantContext tenantContext) {
        this.repo = repo;
        this.tenantContext = tenantContext;
    }

    @Transactional
    public PharmacySettingsResponse getSettings(UUID tenantId) {
        tenantContext.set(tenantId);
        return new PharmacySettingsResponse(repo.findMode(tenantId).orElse("external"));
    }

    @Transactional
    public PharmacySettingsResponse updateSettings(UUID tenantId, PharmacySettingsWriteRequest req) {
        tenantContext.set(tenantId);
        repo.upsertMode(tenantId, req.mode());
        return new PharmacySettingsResponse(req.mode());
    }

    @Transactional
    public List<PharmacyItemResponse> listItems(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.listItems(tenantId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public PharmacyItemResponse addItem(UUID tenantId, PharmacyItemWriteRequest req) {
        tenantContext.set(tenantId);
        requireHybridMode(tenantId);
        String code = "PHM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID id = repo.insertItem(tenantId, code, req);
        return toResponse(repo.findItem(tenantId, id).orElseThrow(this::notFound));
    }

    @Transactional
    public PharmacyItemResponse updateItem(UUID tenantId, UUID id, PharmacyItemWriteRequest req) {
        tenantContext.set(tenantId);
        requireHybridMode(tenantId);
        if (repo.updateItem(tenantId, id, req) == 0) {
            throw notFound();
        }
        return toResponse(repo.findItem(tenantId, id).orElseThrow(this::notFound));
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
                .map(i -> new DispensingQueueEntryResponse.Item(i.drugName(), i.dosage(), i.frequency(), i.duration(), i.instructions()))
                .toList();
        return new DispensingQueueEntryResponse(r.prescriptionId(), r.queueEntryId(), r.patientId(), r.patientName(),
                r.doctorName(), r.signedAt(), items);
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
