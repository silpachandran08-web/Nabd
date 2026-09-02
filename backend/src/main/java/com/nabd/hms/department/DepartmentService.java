package com.nabd.hms.department;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.department.dto.DepartmentResponse;
import com.nabd.hms.department.dto.DepartmentWriteRequest;
import com.nabd.hms.department.dto.TransferEdge;
import com.nabd.hms.department.dto.TransferGraphRequest;
import com.nabd.hms.department.dto.TransferTargetResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static com.nabd.hms.department.DepartmentModels.DepartmentRow;
import static com.nabd.hms.department.DepartmentModels.TransferEdgeRow;

@Service
public class DepartmentService {

    private static final Logger log = LoggerFactory.getLogger(DepartmentService.class);

    private final DepartmentRepository repo;
    private final TenantContext tenantContext;

    DepartmentService(DepartmentRepository repo, TenantContext tenantContext) {
        this.repo = repo;
        this.tenantContext = tenantContext;
    }

    @Transactional
    public List<DepartmentResponse> list(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.list(tenantId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public DepartmentResponse create(UUID tenantId, UUID callerStaffId, DepartmentWriteRequest req) {
        tenantContext.set(tenantId);
        UUID id;
        try {
            id = repo.insert(tenantId, req.name(), req.requiresVitals());
        } catch (DuplicateKeyException e) {
            throw duplicateName();
        }
        log.info("department {} '{}' created by {} (tenant {})", id, req.name(), callerStaffId, tenantId);
        return repo.findById(tenantId, id).map(this::toResponse).orElseThrow();
    }

    @Transactional
    public DepartmentResponse update(UUID tenantId, UUID callerStaffId, UUID id, DepartmentWriteRequest req) {
        tenantContext.set(tenantId);
        DepartmentRow current = repo.findById(tenantId, id).orElseThrow(this::notFound);
        // The default department is the check-in fallback for any doctor with no department
        // assigned (see QueueRepository.findCheckInDepartment) — it must always exist and stay
        // active, so renaming is fine but deactivating it would silently break check-in.
        if (current.isDefault() && !req.active()) {
            throw defaultDepartmentMustStayActive();
        }
        try {
            repo.update(tenantId, id, req.name(), req.requiresVitals(), req.active());
        } catch (DuplicateKeyException e) {
            throw duplicateName();
        }
        log.info("department {} updated by {} (tenant {})", id, callerStaffId, tenantId);
        return repo.findById(tenantId, id).map(this::toResponse).orElseThrow();
    }

    @Transactional
    public List<TransferEdge> listTransfers(UUID tenantId) {
        tenantContext.set(tenantId);
        return repo.findTransfers(tenantId).stream()
                .map(e -> new TransferEdge(e.fromDepartmentId(), e.toDepartmentId())).toList();
    }

    @Transactional
    public List<TransferEdge> replaceTransfers(UUID tenantId, UUID callerStaffId, TransferGraphRequest req) {
        tenantContext.set(tenantId);
        List<TransferEdgeRow> edges = req.edges().stream()
                .map(e -> new TransferEdgeRow(e.fromDepartmentId(), e.toDepartmentId())).toList();
        repo.replaceTransfers(tenantId, edges);
        log.info("department transfer graph replaced by {} (tenant {}, {} edges)", callerStaffId, tenantId, edges.size());
        return listTransfers(tenantId);
    }

    @Transactional
    public List<TransferTargetResponse> transferTargets(UUID tenantId, UUID fromDepartmentId) {
        tenantContext.set(tenantId);
        repo.findById(tenantId, fromDepartmentId).orElseThrow(this::notFound);
        return repo.listTransferTargets(tenantId, fromDepartmentId).stream()
                .map(t -> new TransferTargetResponse(t.departmentId(), t.departmentName(), t.doctors()))
                .toList();
    }

    private DepartmentResponse toResponse(DepartmentRow row) {
        return new DepartmentResponse(row.id(), row.name(), row.requiresVitals(), row.isDefault(), row.active());
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }

    private ApiException duplicateName() {
        return new ApiException(HttpStatus.CONFLICT, "duplicate-department-name", "Department name already in use",
                "A department with this name already exists.");
    }

    private ApiException defaultDepartmentMustStayActive() {
        return new ApiException(HttpStatus.BAD_REQUEST, "default-department-immutable", "Default department must stay active",
                "This is the clinic's fallback department and can't be deactivated.");
    }
}
