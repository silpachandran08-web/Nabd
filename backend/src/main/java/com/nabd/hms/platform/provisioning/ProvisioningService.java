package com.nabd.hms.platform.provisioning;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.platform.provisioning.dto.CreateProvisioningJobRequest;
import com.nabd.hms.platform.provisioning.dto.ProvisioningJobResponse;
import com.nabd.hms.platform.provisioning.dto.ProvisioningStepResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

import static com.nabd.hms.platform.provisioning.ProvisioningModels.Job;
import static com.nabd.hms.platform.provisioning.ProvisioningModels.JobStep;

/**
 * Orchestrates the six-step provisioning job (NB-258) — deliberately NOT @Transactional at this
 * level. Each step runs in its own transaction via {@link ProvisioningStepRunner} (a real Spring
 * bean call, not self-invocation, so @Transactional actually applies); a step failure must still
 * leave that step's own "failed" status durably recorded, which a single outer transaction spanning
 * both the step's work and its bookkeeping could not guarantee — Postgres aborts the whole
 * transaction on the first failed statement, so the failure write has to happen outside it.
 */
@Service
public class ProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningService.class);

    private static final List<String> STEP_ORDER = List.of(
            "create_tenant", "migrate_schema", "seed_masters",
            "provision_whatsapp", "verify_invite_owner", "go_live"
    );

    private final ProvisioningRepository repo;
    private final ProvisioningStepRunner stepRunner;

    ProvisioningService(ProvisioningRepository repo, ProvisioningStepRunner stepRunner) {
        this.repo = repo;
        this.stepRunner = stepRunner;
    }

    public ProvisioningJobResponse createJob(CreateProvisioningJobRequest req, UUID requestedBy) {
        UUID jobId = UUID.randomUUID();
        repo.insertJob(jobId, requestedBy, req.tenantSlug().toLowerCase(), req.tenantName(), req.region(),
                req.ownerEmail().toLowerCase(), req.ownerName(), req.ownerMobile(), req.brandName(), req.path());
        for (int i = 0; i < STEP_ORDER.size(); i++) {
            repo.insertStep(UUID.randomUUID(), jobId, STEP_ORDER.get(i), i + 1);
        }
        log.info("provisioning job {} queued for tenant slug {} by operator {} (path {})",
                jobId, req.tenantSlug(), requestedBy, req.path());
        return toResponse(jobId);
    }

    /**
     * Both paths run through the identical step engine below (SSA-01/SCS-01) — this is the only
     * place they differ. Self-serve has no gate, so isGatedAndUnapproved() is always false for it.
     */
    public ProvisioningJobResponse approve(UUID jobId, UUID approvedBy) {
        Job job = repo.findJob(jobId).orElseThrow(this::notFound);
        if (!"enterprise".equals(job.path())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "not-enterprise-path", "Not an enterprise-path job",
                    "Only enterprise-path jobs require approval; this job has no gate to approve.");
        }
        if (repo.approveJob(jobId, approvedBy)) {
            log.info("provisioning job {} approved by operator {}", jobId, approvedBy);
        }
        return toResponse(jobId);
    }

    public ProvisioningJobResponse getJob(UUID jobId) {
        if (repo.findJob(jobId).isEmpty()) {
            throw notFound();
        }
        return toResponse(jobId);
    }

    public List<ProvisioningJobResponse> listJobs() {
        return repo.listJobs().stream().map(j -> toResponse(j.id())).toList();
    }

    /** Runs the next incomplete step (a queued first attempt, or a retry of the step that last failed). Idempotent once done. */
    public ProvisioningJobResponse advance(UUID jobId) {
        Job job = repo.findJob(jobId).orElseThrow(this::notFound);
        if (job.isGatedAndUnapproved()) {
            return toResponse(jobId); // enterprise job awaiting approval — same no-op idiom as "already done"
        }
        JobStep next = repo.findNextIncompleteStep(jobId).orElse(null);
        if (next == null) {
            return toResponse(jobId); // every step already done
        }

        repo.markStepRunning(next.id());
        repo.updateJobStatus(jobId, "running");
        String ownerInviteToken = null;
        try {
            ownerInviteToken = stepRunner.run(job, next.stepName());
            repo.markStepDone(next.id());
            boolean allDone = !repo.hasIncompleteSteps(jobId);
            repo.updateJobStatus(jobId, allDone ? "done" : "running");
            if (allDone) {
                log.info("provisioning job {} completed", jobId);
            }
        } catch (Exception e) {
            log.warn("provisioning job {} step {} failed", jobId, next.stepName(), e);
            repo.markStepFailed(next.id(), summarize(e));
            if (e instanceof FatalProvisioningException) {
                rollback(jobId, job);
            } else {
                repo.updateJobStatus(jobId, "failed"); // ordinary/transient failure — stays retryable via advance()
            }
        }
        return toResponse(jobId, ownerInviteToken);
    }

    /**
     * A fatal failure (one retrying can never fix, e.g. the region lock) undoes every step that had
     * already committed 'done' — in reverse step order, since seed_masters' role row is a child of
     * create_tenant's tenant row and must go first. The step that actually failed stays 'failed';
     * only the steps whose completed work gets undone become 'rolled_back'.
     */
    private void rollback(UUID jobId, Job job) {
        List<JobStep> steps = repo.findSteps(jobId);
        for (int i = steps.size() - 1; i >= 0; i--) {
            JobStep step = steps.get(i);
            if ("done".equals(step.status())) {
                stepRunner.undo(job, step.stepName());
                repo.markStepRolledBack(step.id());
            }
        }
        repo.updateJobStatus(jobId, "rolled_back");
        log.warn("provisioning job {} rolled back after a fatal failure", jobId);
    }

    private String summarize(Exception e) {
        if (e instanceof DuplicateKeyException) {
            return "tenant slug already exists";
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private ProvisioningJobResponse toResponse(UUID jobId) {
        return toResponse(jobId, null);
    }

    /** ownerInviteToken is reveal-once — only advance() ever has one to pass, right after verify_invite_owner runs. */
    private ProvisioningJobResponse toResponse(UUID jobId, String ownerInviteToken) {
        Job job = repo.findJob(jobId).orElseThrow(this::notFound);
        List<ProvisioningStepResponse> steps = repo.findSteps(jobId).stream()
                .map(s -> new ProvisioningStepResponse(s.stepName(), s.stepOrder(), s.status(),
                        s.startedAt(), s.completedAt(), s.errorDetail()))
                .toList();
        return new ProvisioningJobResponse(job.id(), job.tenantSlug(), job.tenantName(), job.region(),
                job.status(), job.path(), job.approvedAt(), job.createdTenantId(), steps, ownerInviteToken);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found",
                "The requested provisioning job was not found.");
    }
}
