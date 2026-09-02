package com.nabd.hms.department;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.department.dto.DepartmentResponse;
import com.nabd.hms.department.dto.DepartmentWriteRequest;
import com.nabd.hms.department.dto.FlowStepInput;
import com.nabd.hms.department.dto.FlowStepResponse;
import com.nabd.hms.department.dto.FlowWriteRequest;
import com.nabd.hms.department.dto.TransferEdge;
import com.nabd.hms.department.dto.TransferGraphRequest;
import com.nabd.hms.department.dto.TransferTargetResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nabd.hms.department.DepartmentModels.DepartmentRow;
import static com.nabd.hms.department.DepartmentModels.FlowStepRow;
import static com.nabd.hms.department.DepartmentModels.TransferEdgeRow;

@Service
public class DepartmentService {

    private static final Logger log = LoggerFactory.getLogger(DepartmentService.class);

    /** No flow configured yet for a department = today's pre-flow-designer default: vitals then
     * consultation — same fallback V39's migration backfill encoded for every existing department. */
    private static final List<String> DEFAULT_FLOW_STEP_TYPES = List.of("vitals", "consultation");

    /** Fixed anchors (checked_in/waiting first, checkout_pending/completed last) sandwich the
     * tenant-configured middle steps, each expanded to the queue status/es it actually passes
     * through. Single source of truth for the pipeline shape — QueueService and CheckoutService
     * both call resolveStatusSequence() rather than duplicating this. */
    private static final Map<String, List<String>> STATUSES_FOR_STEP_TYPE = Map.of(
            "billing", List.of("billing_pending"),
            "vitals", List.of("vitals_pending", "vitals_done"),
            "consultation", List.of("in_consult"),
            "procedures", List.of("procedures_pending")
    );

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
            id = repo.insert(tenantId, req.name());
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
            repo.update(tenantId, id, req.name(), req.active());
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

    // ── visit flow ──

    @Transactional
    public List<FlowStepResponse> listFlow(UUID tenantId, UUID departmentId) {
        tenantContext.set(tenantId);
        repo.findById(tenantId, departmentId).orElseThrow(this::notFound);
        return repo.findFlowSteps(tenantId, departmentId).stream().map(this::toFlowResponse).toList();
    }

    @Transactional
    public List<FlowStepResponse> replaceFlow(UUID tenantId, UUID callerStaffId, UUID departmentId, FlowWriteRequest req) {
        tenantContext.set(tenantId);
        repo.findById(tenantId, departmentId).orElseThrow(this::notFound);

        List<String> stepTypes = req.steps().stream().map(FlowStepInput::stepType).toList();
        if (stepTypes.stream().filter("consultation"::equals).count() != 1) {
            throw flowInvalid("The flow must include exactly one consultation step.");
        }
        if (stepTypes.size() != stepTypes.stream().distinct().count()) {
            throw flowInvalid("Each step type can appear at most once.");
        }

        List<FlowStepRow> rows = req.steps().stream()
                .map(s -> new FlowStepRow(s.stepType(), s.staffingDepartmentId(), null)).toList();
        repo.replaceFlowSteps(tenantId, departmentId, rows);
        log.info("department {} visit flow replaced by {} (tenant {}, {} steps)", departmentId, callerStaffId, tenantId, rows.size());
        return listFlow(tenantId, departmentId);
    }

    /** The single source of truth for a department's pipeline shape: checked_in/waiting first,
     * checkout_pending/completed last, with this department's configured (or, if unconfigured,
     * the default vitals+consultation) steps expanded to their queue statuses in between. Called
     * by both QueueService (to compute legal transitions) and CheckoutService (to know what
     * status an interim billing stop should advance to). */
    @Transactional
    public List<String> resolveStatusSequence(UUID tenantId, UUID departmentId) {
        tenantContext.set(tenantId);
        List<FlowStepRow> configured = repo.findFlowSteps(tenantId, departmentId);
        List<String> stepTypes = configured.isEmpty()
                ? DEFAULT_FLOW_STEP_TYPES
                : configured.stream().map(FlowStepRow::stepType).toList();

        List<String> sequence = new ArrayList<>(List.of("checked_in", "waiting"));
        for (String stepType : stepTypes) {
            sequence.addAll(STATUSES_FOR_STEP_TYPE.get(stepType));
        }
        sequence.addAll(List.of("checkout_pending", "completed"));
        return sequence;
    }

    private FlowStepResponse toFlowResponse(FlowStepRow row) {
        return new FlowStepResponse(row.stepType(), row.staffingDepartmentId(), row.staffingDepartmentName());
    }

    private DepartmentResponse toResponse(DepartmentRow row) {
        return new DepartmentResponse(row.id(), row.name(), row.isDefault(), row.active());
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

    private ApiException flowInvalid(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "flow-invalid", "Invalid visit flow", detail);
    }
}
