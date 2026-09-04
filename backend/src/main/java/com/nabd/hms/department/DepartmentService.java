package com.nabd.hms.department;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.department.dto.DepartmentResponse;
import com.nabd.hms.department.dto.DepartmentWorkflowRequest;
import com.nabd.hms.department.dto.DepartmentWorkflowResponse;
import com.nabd.hms.department.dto.DepartmentWriteRequest;
import com.nabd.hms.department.dto.FlowStepResponse;
import com.nabd.hms.department.dto.TransferEdge;
import com.nabd.hms.department.dto.TransferGraphRequest;
import com.nabd.hms.department.dto.TransferTargetResponse;
import com.nabd.hms.department.dto.WorkflowTemplateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.department.DepartmentModels.DepartmentRow;
import static com.nabd.hms.department.DepartmentModels.TransferEdgeRow;
import static com.nabd.hms.department.DepartmentModels.WorkflowSelectionRow;
import static com.nabd.hms.department.DepartmentModels.WorkflowTemplateRow;

@Service
public class DepartmentService {

    private static final Logger log = LoggerFactory.getLogger(DepartmentService.class);

    /** No workflow template picked yet for a department = the platform default: the clinic_walkin
     * template with vitals on — same shape as the "clinic_walkin" seed row from V40, so this stays
     * true even if that row's exact steps ever change. */
    private static final List<String> DEFAULT_FLOW_STEP_TYPES = List.of("vitals", "consultation");

    /** Fixed anchors (checked_in/waiting first, checkout_pending/completed last) sandwich the
     * resolved middle steps, each expanded to the queue status/es it actually passes through.
     * Single source of truth for the pipeline shape — QueueService and CheckoutService both call
     * resolveStatusSequence() rather than duplicating this. */
    private static final Map<String, List<String>> STATUSES_FOR_STEP_TYPE = Map.of(
            "billing", List.of("billing_pending"),
            "vitals", List.of("vitals_pending", "vitals_done"),
            "consultation", List.of("in_consult"),
            "procedures", List.of("procedures_pending")
    );

    private final DepartmentRepository repo;
    private final TenantContext tenantContext;
    private final ObjectMapper objectMapper;

    DepartmentService(DepartmentRepository repo, TenantContext tenantContext, ObjectMapper objectMapper) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.objectMapper = objectMapper;
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

    // ── workflow (platform-authored templates; owner picks one plus its toggles) ──

    /** The resolved, ordered step-type list for consult/checkout/nursing pages — empty when
     * nothing's been picked yet (those pages already know to apply the same default themselves,
     * mirroring resolveStatusSequence's fallback). */
    @Transactional
    public List<FlowStepResponse> listFlow(UUID tenantId, UUID departmentId) {
        tenantContext.set(tenantId);
        repo.findById(tenantId, departmentId).orElseThrow(this::notFound);
        return repo.findSelection(tenantId, departmentId)
                .map(this::expandToggledSteps)
                .orElse(List.of())
                .stream().map(FlowStepResponse::new).toList();
    }

    @Transactional
    public DepartmentWorkflowResponse getWorkflow(UUID tenantId, UUID departmentId) {
        tenantContext.set(tenantId);
        repo.findById(tenantId, departmentId).orElseThrow(this::notFound);
        Optional<WorkflowSelectionRow> selection = repo.findSelection(tenantId, departmentId);
        String templateCode = selection.map(WorkflowSelectionRow::templateCode).orElse(null);
        Map<String, Boolean> toggles = selection.map(s -> readToggles(s.togglesJson())).orElse(Map.of());
        List<String> resolvedSteps = selection.map(this::expandToggledSteps).orElse(DEFAULT_FLOW_STEP_TYPES);
        return new DepartmentWorkflowResponse(templateCode, toggles, resolvedSteps, listTemplates());
    }

    @Transactional
    public DepartmentWorkflowResponse replaceWorkflow(UUID tenantId, UUID callerStaffId, UUID departmentId, DepartmentWorkflowRequest req) {
        tenantContext.set(tenantId);
        repo.findById(tenantId, departmentId).orElseThrow(this::notFound);
        WorkflowTemplateRow template = repo.findPlatformTemplate(req.templateCode()).orElseThrow(this::unknownTemplate);

        List<String> allowedToggleKeys = readList(template.toggleKeysJson());
        Map<String, Boolean> toggles = req.toggles() == null ? Map.of() : req.toggles();
        for (String key : toggles.keySet()) {
            if (!allowedToggleKeys.contains(key)) {
                throw workflowInvalid("'" + key + "' isn't a toggle on the " + template.name() + " template.");
            }
        }

        repo.upsertSelection(tenantId, departmentId, template.id(), writeJson(toggles));
        log.info("department {} workflow set to template {} by {} (tenant {})", departmentId, req.templateCode(), callerStaffId, tenantId);
        return getWorkflow(tenantId, departmentId);
    }

    /** The single source of truth for a department's pipeline shape: checked_in/waiting first,
     * checkout_pending/completed last, with this department's resolved template steps (or, if
     * unconfigured, the default vitals+consultation) expanded to their queue statuses in between.
     * Called by both QueueService (to compute legal transitions) and CheckoutService (to know what
     * status an interim billing stop should advance to). */
    @Transactional
    public List<String> resolveStatusSequence(UUID tenantId, UUID departmentId) {
        tenantContext.set(tenantId);
        List<String> stepTypes = repo.findSelection(tenantId, departmentId)
                .map(this::expandToggledSteps)
                .orElse(DEFAULT_FLOW_STEP_TYPES);

        List<String> sequence = new ArrayList<>(List.of("checked_in", "waiting"));
        for (String stepType : stepTypes) {
            sequence.addAll(STATUSES_FOR_STEP_TYPE.get(stepType));
        }
        sequence.addAll(List.of("checkout_pending", "completed"));
        return sequence;
    }

    /** A template's own step order, minus any step a false toggle switches off (currently only
     * vitals_enabled). Toggles never reorder or add steps — only the platform template does that. */
    private List<String> expandToggledSteps(WorkflowSelectionRow selection) {
        List<String> steps = new ArrayList<>(readList(selection.stepsJson()));
        if (Boolean.FALSE.equals(readToggles(selection.togglesJson()).get("vitals_enabled"))) {
            steps.remove("vitals");
        }
        return steps;
    }

    private List<WorkflowTemplateResponse> listTemplates() {
        return repo.listPlatformTemplates().stream()
                .map(t -> new WorkflowTemplateResponse(t.code(), t.name(), readList(t.stepsJson()), readList(t.toggleKeysJson())))
                .toList();
    }

    private List<String> readList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("malformed workflow JSON", e);
        }
    }

    private Map<String, Boolean> readToggles(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Boolean>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("malformed toggles JSON", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize toggles", e);
        }
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

    private ApiException unknownTemplate() {
        return new ApiException(HttpStatus.BAD_REQUEST, "unknown-workflow-template", "Unknown workflow template",
                "That template code isn't in the platform's published library.");
    }

    private ApiException workflowInvalid(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "workflow-invalid", "Invalid workflow selection", detail);
    }
}
