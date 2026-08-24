package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.ConditionResponse;
import com.nabd.hms.clinical.dto.ConditionWriteRequest;
import com.nabd.hms.clinical.dto.DueConditionResponse;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.nabd.hms.clinical.ConditionModels.ConditionRow;

/** NB-077: mirrors AllergyService — a patient-level problem list, carried forward between
 * encounters by simply being patient-scoped rather than visit-scoped. */
@Service
public class ConditionService {

    private final ConditionRepository repo;
    private final TenantContext tenantContext;

    ConditionService(ConditionRepository repo, TenantContext tenantContext) {
        this.repo = repo;
        this.tenantContext = tenantContext;
    }

    @Transactional
    public List<ConditionResponse> list(UUID tenantId, UUID patientId) {
        tenantContext.set(tenantId);
        return repo.findActiveByPatient(tenantId, patientId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public ConditionResponse add(UUID tenantId, UUID patientId, UUID callerStaffId, ConditionWriteRequest req) {
        tenantContext.set(tenantId);
        UUID id = repo.insert(tenantId, patientId, req.condition(), req.reviewDueDate(), callerStaffId);
        return repo.findActiveByPatient(tenantId, patientId).stream()
                .filter(c -> c.id().equals(id)).findFirst().map(this::toResponse).orElseThrow();
    }

    @Transactional
    public void resolve(UUID tenantId, UUID id) {
        tenantContext.set(tenantId);
        if (repo.resolve(tenantId, id) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
        }
    }

    @Transactional
    public List<DueConditionResponse> due(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.findDue(tenantId, LocalDate.now(ZoneOffset.UTC)).stream()
                .map(d -> new DueConditionResponse(d.id(), d.patientId(), d.patientName(), d.condition(), d.reviewDueDate()))
                .toList();
    }

    private ConditionResponse toResponse(ConditionRow c) {
        return new ConditionResponse(c.id(), c.patientId(), c.condition(), c.status(), c.reviewDueDate(),
                c.recordedBy(), c.recordedAt());
    }
}
