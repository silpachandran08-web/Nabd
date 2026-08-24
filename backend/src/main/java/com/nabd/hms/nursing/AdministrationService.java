package com.nabd.hms.nursing;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.nursing.dto.AdministerRequest;
import com.nabd.hms.nursing.dto.AdministrationOrderRequest;
import com.nabd.hms.nursing.dto.AdministrationOrderResponse;
import com.nabd.hms.nursing.dto.RefuseRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.nursing.NursingModels.AdministrationOrderRow;
import static com.nabd.hms.nursing.NursingModels.AdministrationRecordRow;

/**
 * NB-145: injection/infusion orders with an immutable administer/witness/refuse-with-reason
 * outcome. The "five rights" (patient, drug, dose, route, time) are a nurse-side confirmation step
 * before submitting — there's no database column that could mechanically verify them, only the
 * order this record points back to.
 */
@Service
public class AdministrationService {

    private final NursingRepository repo;
    private final TenantContext tenantContext;

    AdministrationService(NursingRepository repo, TenantContext tenantContext) {
        this.repo = repo;
        this.tenantContext = tenantContext;
    }

    @Transactional
    public AdministrationOrderResponse order(UUID tenantId, UUID staffId, AdministrationOrderRequest req) {
        tenantContext.set(tenantId);
        UUID patientId = repo.findPatientIdForQueueEntry(tenantId, req.queueEntryId()).orElseThrow(this::notFound);
        UUID id = repo.insertAdministrationOrder(tenantId, req.queueEntryId(), patientId, staffId, req.drugName(),
                req.dose(), req.route(), req.site());
        return toResponse(tenantId, repo.findAdministrationOrder(tenantId, id).orElseThrow());
    }

    @Transactional
    public List<AdministrationOrderResponse> listToday(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.listAdministrationOrders(tenantId, LocalDate.now()).stream().map(o -> toResponse(tenantId, o)).toList();
    }

    @Transactional
    public AdministrationOrderResponse administer(UUID tenantId, UUID staffId, UUID orderId, AdministerRequest req) {
        tenantContext.set(tenantId);
        repo.findAdministrationOrder(tenantId, orderId).orElseThrow(this::notFound);
        if (staffId.equals(req.witnessedByStaffId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "self-witness", "Cannot self-witness",
                    "The witness must be a different staff member from whoever is administering.");
        }
        repo.insertAdministrationRecord(tenantId, orderId, "administered", staffId, req.witnessedByStaffId(), null);
        return toResponse(tenantId, repo.findAdministrationOrder(tenantId, orderId).orElseThrow());
    }

    @Transactional
    public AdministrationOrderResponse refuse(UUID tenantId, UUID staffId, UUID orderId, RefuseRequest req) {
        tenantContext.set(tenantId);
        repo.findAdministrationOrder(tenantId, orderId).orElseThrow(this::notFound);
        repo.insertAdministrationRecord(tenantId, orderId, "refused", staffId, null, req.reason());
        return toResponse(tenantId, repo.findAdministrationOrder(tenantId, orderId).orElseThrow());
    }

    private AdministrationOrderResponse toResponse(UUID tenantId, AdministrationOrderRow row) {
        Optional<AdministrationRecordRow> record = repo.findAdministrationRecord(tenantId, row.id());
        String patientName = repo.findPatientName(tenantId, row.patientId()).orElse("Unknown patient");
        String orderedByName = repo.findStaffName(tenantId, row.orderedBy()).orElse("Unknown staff");
        String status = record.map(AdministrationRecordRow::action).orElse("not_started");
        String recordedByName = record.map(r -> repo.findStaffName(tenantId, r.recordedBy()).orElse("Unknown staff")).orElse(null);
        String witnessedByName = record.map(AdministrationRecordRow::witnessedBy)
                .flatMap(id -> id == null ? Optional.<String>empty() : repo.findStaffName(tenantId, id)).orElse(null);
        String refuseReason = record.map(AdministrationRecordRow::refuseReason).orElse(null);
        var recordedAt = record.map(AdministrationRecordRow::recordedAt).orElse(null);
        return new AdministrationOrderResponse(row.id(), row.queueEntryId(), row.patientId(), patientName, orderedByName,
                row.drugName(), row.dose(), row.route(), row.site(), status, recordedByName, witnessedByName,
                refuseReason, recordedAt, row.createdAt());
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }
}
