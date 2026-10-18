package com.nabd.hms.platform.plans;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.platform.PlatformAudit;
import com.nabd.hms.platform.plans.dto.PlanResponse;
import com.nabd.hms.platform.plans.dto.PlanWriteRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static com.nabd.hms.platform.plans.PlanModels.Plan;

@Service
public class PlanService {

    private final PlanRepository repo;
    private final PlatformAudit audit;

    PlanService(PlanRepository repo, PlatformAudit audit) {
        this.repo = repo;
        this.audit = audit;
    }

    public List<PlanResponse> list() {
        return repo.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional
    public PlanResponse create(PlanWriteRequest req, UUID operatorId, String ip) {
        UUID id;
        try {
            id = repo.insert(req.code(), req.name(), req.monthlyPriceCents(), req.currency(), req.seatLimit(), req.active());
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "plan-code-conflict", "Plan code already exists",
                    "A plan with this code already exists.");
        }
        PlanResponse created = toResponse(repo.findById(id).orElseThrow());
        audit.record(null, operatorId, ip, "platform_plan.created", "plan", id, created);
        return created;
    }

    @Transactional
    public PlanResponse update(UUID id, PlanWriteRequest req, UUID operatorId, String ip) {
        PlanResponse before = toResponse(repo.findById(id).orElseThrow(this::notFound));
        repo.update(id, req.code(), req.name(), req.monthlyPriceCents(), req.currency(), req.seatLimit(), req.active());
        PlanResponse after = toResponse(repo.findById(id).orElseThrow());
        audit.record(null, operatorId, ip, "platform_plan.updated", "plan", id, before, after);
        return after;
    }

    private PlanResponse toResponse(Plan p) {
        return new PlanResponse(p.id(), p.code(), p.name(), p.monthlyPriceCents(), p.currency(),
                p.seatLimit(), p.active(), p.createdAt());
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested plan was not found.");
    }
}
