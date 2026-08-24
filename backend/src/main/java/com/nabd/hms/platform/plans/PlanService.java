package com.nabd.hms.platform.plans;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.platform.plans.dto.PlanResponse;
import com.nabd.hms.platform.plans.dto.PlanWriteRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

import static com.nabd.hms.platform.plans.PlanModels.Plan;

@Service
public class PlanService {

    private final PlanRepository repo;

    PlanService(PlanRepository repo) {
        this.repo = repo;
    }

    public List<PlanResponse> list() {
        return repo.findAll().stream().map(this::toResponse).toList();
    }

    public PlanResponse create(PlanWriteRequest req) {
        UUID id;
        try {
            id = repo.insert(req.code(), req.name(), req.monthlyPriceCents(), req.currency(), req.seatLimit(), req.active());
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "plan-code-conflict", "Plan code already exists",
                    "A plan with this code already exists.");
        }
        return toResponse(repo.findById(id).orElseThrow());
    }

    public PlanResponse update(UUID id, PlanWriteRequest req) {
        repo.findById(id).orElseThrow(this::notFound);
        repo.update(id, req.code(), req.name(), req.monthlyPriceCents(), req.currency(), req.seatLimit(), req.active());
        return toResponse(repo.findById(id).orElseThrow());
    }

    private PlanResponse toResponse(Plan p) {
        return new PlanResponse(p.id(), p.code(), p.name(), p.monthlyPriceCents(), p.currency(),
                p.seatLimit(), p.active(), p.createdAt());
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested plan was not found.");
    }
}
