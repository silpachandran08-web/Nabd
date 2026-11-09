package com.nabd.hms.platform.plans;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.platform.PlatformAudit;
import com.nabd.hms.platform.entitlements.PlanEntitlements;
import com.nabd.hms.platform.plans.dto.PlanCatalogueEntry;
import com.nabd.hms.platform.plans.dto.PlanResponse;
import com.nabd.hms.platform.plans.dto.PlanVersionResponse;
import com.nabd.hms.platform.plans.dto.PlanWriteRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.nabd.hms.platform.plans.PlanModels.Plan;

/**
 * Plans are versioned (ADM-034): any change to price, seats, branches, visits or modules appends a version; a
 * subscription is pinned to the version it was sold (master.subscriptions.plan_version) until an operator moves it, so
 * editing a plan never changes what a clinic already has. Version rows are appended, never rewritten or deleted.
 */
@Service
public class PlanService {

    private static final String NOT_FOUND = "The requested plan was not found.";

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
        List<String> modules = validModules(req.modules());
        Integer seats = req.seatLimit() != null ? req.seatLimit() : req.limits() == null ? null : req.limits().seats();
        if (seats == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "seats-required", "Seat limit required", "Give limits.seats (or seatLimit).");
        }
        Integer branches = req.limits() == null ? null : req.limits().branches();
        Integer visits = req.limits() == null ? null : req.limits().visitsPerMonth();
        UUID id;
        try {
            id = repo.insert(req.code(), req.name(), req.monthlyPriceCents(), req.currency(), seats, req.active(), req.market(),
                    req.pricingBasis() == null ? "per_clinic" : req.pricingBasis(), branches, visits, req.onTrialExpiry());
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "plan-code-conflict", "CODE_EXISTS",
                    "A plan with this code already exists.");
        }
        repo.publishVersion(id, 1, req.monthlyPriceCents(), seats, branches, visits, modules);
        PlanResponse created = toResponse(repo.findById(id).orElseThrow());
        audit.record(null, operatorId, ip, "platform_plan.created", "plan", id, created);
        return created;
    }

    @Transactional
    public PlanResponse update(UUID id, PlanWriteRequest req, UUID operatorId, String ip) {
        Plan cur = repo.findById(id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        PlanResponse before = toResponse(cur);
        List<String> curModules = repo.modules(id, cur.version());
        List<String> modules = req.modules() == null ? curModules : validModules(req.modules());
        int seats = req.seatLimit() != null ? req.seatLimit()
                : req.limits() != null && req.limits().seats() != null ? req.limits().seats() : cur.seatLimit();
        Integer branches = req.limits() != null && req.limits().branches() != null ? req.limits().branches() : cur.branchLimit();
        Integer visits = req.limits() != null && req.limits().visitsPerMonth() != null ? req.limits().visitsPerMonth() : cur.visitsPerMonth();
        try {
            repo.update(id, req.code(), req.name(), req.monthlyPriceCents(), req.currency(), seats, req.active(),
                    req.market() != null ? req.market() : cur.market(),
                    req.pricingBasis() != null ? req.pricingBasis() : cur.pricingBasis(), branches, visits,
                    req.onTrialExpiry() != null ? req.onTrialExpiry() : cur.onTrialExpiry());
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "plan-code-conflict", "CODE_EXISTS", "A plan with this code already exists.");
        }
        boolean changed = req.monthlyPriceCents() != cur.monthlyPriceCents() || seats != cur.seatLimit()
                || !Objects.equals(branches, cur.branchLimit()) || !Objects.equals(visits, cur.visitsPerMonth())
                || !new HashSet<>(modules).equals(new HashSet<>(curModules));
        if (changed) {
            repo.publishVersion(id, cur.version() + 1, req.monthlyPriceCents(), seats, branches, visits, modules);
        }
        PlanResponse after = toResponse(repo.findById(id).orElseThrow());
        audit.record(null, operatorId, ip, "platform_plan.updated", "plan", id, before, after);
        return after;
    }

    @Transactional(readOnly = true)
    public List<PlanVersionResponse> versions(UUID id) {
        repo.findById(id).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        return repo.versions(id).stream().map(v -> new PlanVersionResponse((int) v[0], (int) v[1], (int) v[2], (Integer) v[3],
                (Integer) v[4], repo.modules(id, (int) v[0]), (Instant) v[5], (long) v[6])).toList();
    }

    /** Public comparison: current version of every active plan offered in the market. */
    @Transactional(readOnly = true)
    public List<PlanCatalogueEntry> catalogue(String market) {
        return repo.catalogue(market == null || market.isBlank() ? null : market).stream()
                .map(p -> new PlanCatalogueEntry(p.code(), p.name(), p.monthlyPriceCents(), p.currency(), p.pricingBasis(),
                        repo.modules(p.id(), p.version()),
                        new PlanCatalogueEntry.Limits(p.seatLimit(), p.branchLimit(), p.visitsPerMonth())))
                .toList();
    }

    private static List<String> validModules(List<String> modules) {
        if (modules == null) return List.of();
        for (String m : modules) {
            if (!PlanEntitlements.MODULES.contains(m)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "unknown-module", "UNKNOWN_MODULE", "Unknown module code.");
            }
        }
        return modules.stream().distinct().toList();
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    PlanResponse toResponse(Plan p) {
        return new PlanResponse(p.id(), p.code(), p.name(), p.monthlyPriceCents(), p.currency(),
                p.seatLimit(), p.active(), p.createdAt(), p.version(), p.market(), p.pricingBasis(), p.branchLimit(),
                p.visitsPerMonth(), p.onTrialExpiry(), repo.modules(p.id(), p.version()));
    }

}
