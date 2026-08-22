package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.VitalsResponse;
import com.nabd.hms.clinical.dto.VitalsWriteRequest;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.queue.QueueService;
import com.nabd.hms.queue.dto.QueueStatusUpdateRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.nabd.hms.clinical.VitalsModels.QueueEntrySnapshot;
import static com.nabd.hms.clinical.VitalsModels.VitalsRow;

/**
 * NB-106: closes a real gap, not a speculative one — QueueService's own state machine requires
 * vitals_pending -> vitals_done before a doctor can start a consult, and until this existed nothing
 * in the app could make that transition short of a raw status PATCH. Reuses QueueService directly
 * (same pattern as CheckoutService/NoteService reusing it) rather than duplicating the transition.
 */
@Service
public class VitalsService {

    private final VitalsRepository repo;
    private final QueueService queueService;
    private final TenantContext tenantContext;

    VitalsService(VitalsRepository repo, QueueService queueService, TenantContext tenantContext) {
        this.repo = repo;
        this.queueService = queueService;
        this.tenantContext = tenantContext;
    }

    @Transactional
    public VitalsResponse get(UUID tenantId, UUID queueEntryId) {
        tenantContext.set(tenantId);
        VitalsRow row = repo.findByQueueEntry(tenantId, queueEntryId).orElseThrow(this::notFound);
        return toResponse(tenantId, row);
    }

    @Transactional
    public VitalsResponse record(UUID tenantId, UUID callerStaffId, UUID queueEntryId, VitalsWriteRequest req) {
        tenantContext.set(tenantId);
        QueueEntrySnapshot snapshot = repo.findQueueEntrySnapshot(tenantId, queueEntryId).orElseThrow(this::notFound);

        repo.upsert(tenantId, queueEntryId, snapshot.patientId(), req.heightCm(), req.weightKg(),
                req.bpSystolic(), req.bpDiastolic(), req.pulseBpm(), req.tempCelsius(), req.spo2Percent(),
                callerStaffId);

        if ("vitals_pending".equals(snapshot.status())) {
            queueService.updateStatus(tenantId, callerStaffId, queueEntryId, new QueueStatusUpdateRequest("vitals_done"));
        }
        VitalsRow row = repo.findByQueueEntry(tenantId, queueEntryId).orElseThrow(this::notFound);
        return toResponse(tenantId, row);
    }

    private VitalsResponse toResponse(UUID tenantId, VitalsRow v) {
        int ageYears = repo.findPatientDob(tenantId, v.patientId()).map(this::ageInYears).orElse(30);
        List<String> flags = VitalsRanges.flags(v, ageYears);
        return new VitalsResponse(v.id(), v.queueEntryId(), v.patientId(), v.heightCm(), v.weightKg(),
                v.bpSystolic(), v.bpDiastolic(), v.pulseBpm(), v.tempCelsius(), v.spo2Percent(),
                v.recordedBy(), v.recordedAt(), flags);
    }

    private int ageInYears(LocalDate dob) {
        return Period.between(dob, LocalDate.now(ZoneOffset.UTC)).getYears();
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }
}
