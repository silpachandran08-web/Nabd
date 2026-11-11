package com.nabd.hms.staff;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ACC-035: the signed-in staff member's first-run tours and reception competency check. Keys are a fixed allow-list (the
 * frontend's tour roles and the ten desk tasks of the train-the-trainer pack). Ticking the tenth task records the pass once
 * and audits it; a tour key is a per-viewer preference (no audit, same as FEF-036 preferences).
 */
@Service
class StaffOnboardingService {

    static final List<String> TOURS = List.of("tour.reception", "tour.nurse", "tour.doctor", "tour.owner");
    /** The ten reception competency tasks (docs/training/reception-train-the-trainer.md, same order). */
    static final List<String> TASKS = List.of("task.walkin", "task.checkin", "task.queue", "task.requests", "task.delay",
            "task.checkout", "task.reschedule", "task.noshow", "task.search", "task.offline");
    static final String COMPETENCY = "competency.reception";

    @Schema(name = "StaffOnboardingProgress")
    record Progress(List<String> completed, Instant competencyPassedAt) {
    }

    private final StaffOnboardingRepository repo;
    private final TenantContext tenantContext;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final Clock clock;

    StaffOnboardingService(StaffOnboardingRepository repo, TenantContext tenantContext, AuditService audit, ActorDirectory actors, Clock clock) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.audit = audit;
        this.actors = actors;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Progress get(UUID staffId) {
        UUID tenantId = tenantContext.current();
        return new Progress(repo.completed(tenantId, staffId), repo.passedAt(tenantId, staffId, COMPETENCY).orElse(null));
    }

    @Transactional
    public Progress complete(UUID staffId, String key) {
        if (!TOURS.contains(key) && !TASKS.contains(key)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "unknown-onboarding-key", "Unknown tour or task",
                    "No tour or competency task has this key.", Map.of("code", "UNKNOWN_ONBOARDING_KEY"));
        }
        UUID tenantId = tenantContext.current();
        Instant now = clock.instant();
        repo.complete(tenantId, staffId, key, now);
        List<String> done = repo.completed(tenantId, staffId);
        if (TASKS.contains(key) && new HashSet<>(done).containsAll(TASKS) && repo.pass(tenantId, staffId, COMPETENCY, now)) {
            ActorDirectory.Actor a = actors.findActor(tenantId, staffId).orElseThrow(ApiException::notFound);
            audit.record(tenantId, "staff", staffId, a.name(), a.role(), null, "staff.competency_passed", "staff", staffId, null,
                    Map.of("check", COMPETENCY, "tasks", TASKS.size()));
        }
        return new Progress(done, repo.passedAt(tenantId, staffId, COMPETENCY).orElse(null));
    }
}
