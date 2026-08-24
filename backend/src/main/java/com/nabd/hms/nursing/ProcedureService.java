package com.nabd.hms.nursing;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.nursing.dto.ProcedureConsentRequest;
import com.nabd.hms.nursing.dto.ProcedureNotesRequest;
import com.nabd.hms.nursing.dto.ProcedureOrderRequest;
import com.nabd.hms.nursing.dto.ProcedureOrderResponse;
import com.nabd.hms.nursing.dto.ProcedureStatusRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.nabd.hms.nursing.NursingModels.ProcedureOrderRow;
import static com.nabd.hms.nursing.NursingRepository.ChargeSnapshot;

/**
 * NB-146: today's scheduled procedures, prep and completion. "Completion posts a billable event"
 * is CheckoutContextResponse surfacing unbilled completed rows (see CheckoutRepository) so Fast
 * Checkout pre-loads them — a completed procedure was never going to be forgotten at billing time,
 * not a second invoicing pipeline living next to the real one.
 */
@Service
public class ProcedureService {

    private final NursingRepository repo;
    private final TenantContext tenantContext;

    ProcedureService(NursingRepository repo, TenantContext tenantContext) {
        this.repo = repo;
        this.tenantContext = tenantContext;
    }

    @Transactional
    public ProcedureOrderResponse order(UUID tenantId, UUID staffId, ProcedureOrderRequest req) {
        tenantContext.set(tenantId);
        UUID patientId = repo.findPatientIdForQueueEntry(tenantId, req.queueEntryId()).orElseThrow(this::notFound);
        ChargeSnapshot charge = repo.findActiveCharge(tenantId, req.chargeCode())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "charge-not-found", "Charge not found",
                        "chargeCode does not reference an active charge in this clinic's catalogue."));
        UUID id = repo.insertProcedureOrder(tenantId, req.queueEntryId(), patientId, staffId, charge, req.prepNotes(), req.consentNote());
        return toResponse(tenantId, repo.findProcedureOrder(tenantId, id).orElseThrow());
    }

    @Transactional
    public List<ProcedureOrderResponse> listToday(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.listProcedureOrders(tenantId, LocalDate.now()).stream().map(p -> toResponse(tenantId, p)).toList();
    }

    @Transactional
    public ProcedureOrderResponse updateStatus(UUID tenantId, UUID staffId, UUID id, ProcedureStatusRequest req) {
        tenantContext.set(tenantId);
        ProcedureOrderRow row = repo.findProcedureOrder(tenantId, id).orElseThrow(this::notFound);
        if ("completed".equals(row.status()) || "cancelled".equals(row.status())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "already-final", "Already final",
                    "This procedure is already " + row.status() + ".");
        }
        if (!"cancelled".equals(req.status()) && row.consentSignedAt() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "consent-required", "Consent required",
                    "Record the patient's signed consent before starting this procedure.");
        }
        repo.updateProcedureStatus(tenantId, id, req.status(), "completed".equals(req.status()) ? staffId : null);
        return toResponse(tenantId, repo.findProcedureOrder(tenantId, id).orElseThrow());
    }

    /** NB-119: gates updateStatus above — a typed name is this app's stand-in for a signature. */
    @Transactional
    public ProcedureOrderResponse recordConsent(UUID tenantId, UUID staffId, UUID id, ProcedureConsentRequest req) {
        tenantContext.set(tenantId);
        repo.findProcedureOrder(tenantId, id).orElseThrow(this::notFound);
        repo.recordConsent(tenantId, id, req.signedName(), staffId);
        return toResponse(tenantId, repo.findProcedureOrder(tenantId, id).orElseThrow());
    }

    @Transactional
    public ProcedureOrderResponse updateNotes(UUID tenantId, UUID id, ProcedureNotesRequest req) {
        tenantContext.set(tenantId);
        repo.findProcedureOrder(tenantId, id).orElseThrow(this::notFound);
        repo.updateProcedureNotes(tenantId, id, req.prepNotes(), req.consentNote());
        return toResponse(tenantId, repo.findProcedureOrder(tenantId, id).orElseThrow());
    }

    private ProcedureOrderResponse toResponse(UUID tenantId, ProcedureOrderRow row) {
        String patientName = repo.findPatientName(tenantId, row.patientId()).orElse("Unknown patient");
        String orderedByName = repo.findStaffName(tenantId, row.orderedBy()).orElse("Unknown staff");
        String completedByName = row.completedBy() == null ? null : repo.findStaffName(tenantId, row.completedBy()).orElse(null);
        String consentRecordedByName = row.consentRecordedBy() == null ? null
                : repo.findStaffName(tenantId, row.consentRecordedBy()).orElse(null);
        return new ProcedureOrderResponse(row.id(), row.queueEntryId(), row.patientId(), patientName, orderedByName,
                row.chargeCode(), row.chargeName(), row.baseAmount(), row.taxRatePercent(), row.prepNotes(),
                row.consentNote(), row.status(), row.billed(), completedByName, row.completedAt(), row.createdAt(),
                row.consentSignedName(), consentRecordedByName, row.consentSignedAt());
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }
}
