package com.nabd.hms.platform.provisioning;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ConfigBundle;
import com.nabd.hms.common.StepUpVerifier;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.platform.entitlements.PlanEntitlements;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.nabd.hms.platform.provisioning.ProvisioningModels.Branch;
import static com.nabd.hms.platform.provisioning.ProvisioningModels.Job;
import static com.nabd.hms.platform.provisioning.ProvisioningRepository.OwnerLink;

/**
 * PLT-022: the owner adds a branch from their own console. A branch is a new clinic (tenant) under the brand of the clinic the
 * owner is signed in to, so isolation is unchanged. The request becomes a provisioning job (the PLT-058 step engine) that also
 * copies the chosen configuration sections from one of the owner's clinics. The region is never asked for: it is the owner's.
 * Only the brand's owner account holding the built-in Owner role may do any of this, and starting one needs a step-up
 * confirmation bound to the branch code, used once.
 */
@Service
public class OwnerBranchService {

    /** copy_config sits before provision_whatsapp so the copied template languages win over the regional defaults. */
    static final List<String> STEPS = List.of("create_tenant", "seed_masters", "copy_config", "provision_whatsapp", "go_live");
    static final String UPGRADE_URL = "/plan"; // staff-web owner.plan (ADM-037)

    public record Started(UUID provisioningJobId, UUID branchTenantId) {
    }

    public record BranchItem(UUID tenantId, String name, String branchCode, String status, String region, String timezone) {
    }

    public record StepItem(String stepName, String status) {
    }

    /** failedStep: the step the owner can retry from; null while the job is still moving. */
    public record JobItem(UUID provisioningJobId, UUID branchTenantId, String name, String branchCode, String status, String failedStep,
                          List<StepItem> steps) {
    }

    /** branchLimit: null when the plan has no limit. */
    public record BranchList(List<BranchItem> items, List<JobItem> jobs, Integer branchLimit, long branchesInUse) {
    }

    private final ProvisioningRepository repo;
    private final TenantContext tenantContext;
    private final StepUpVerifier stepUp;
    private final PlanEntitlements entitlements;
    private final RealDataOnboardingGuard realDataGuard;
    private final AuditService audit;
    private final BranchProvisioningWorker worker;

    OwnerBranchService(ProvisioningRepository repo, TenantContext tenantContext, StepUpVerifier stepUp, PlanEntitlements entitlements,
                       RealDataOnboardingGuard realDataGuard, AuditService audit, BranchProvisioningWorker worker) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.stepUp = stepUp;
        this.entitlements = entitlements;
        this.realDataGuard = realDataGuard;
        this.audit = audit;
        this.worker = worker;
    }

    /** The step-up "target" the owner asks /v1/auth/mfa/verify for: the confirmation is good for this branch code only. */
    public static String stepUpTarget(String branchCode) {
        return "branch-add:" + branchCode.toLowerCase(Locale.ROOT);
    }

    @Transactional(readOnly = true)
    public BranchList list(UUID staffId) {
        OwnerLink owner = requireOwner(staffId);
        List<BranchItem> items = repo.branchesOf(owner.ownerId()).stream()
                .map(b -> new BranchItem(b.tenantId(), b.name(), b.branchCode(), b.status(), b.region(), b.timezone())).toList();
        List<JobItem> jobs = repo.openBranchJobs(owner.ownerEmail()).stream().map(j -> {
            List<StepItem> steps = repo.findSteps(j.id()).stream().map(s -> new StepItem(s.stepName(), s.status())).toList();
            String failed = steps.stream().filter(s -> "failed".equals(s.status())).map(StepItem::stepName).findFirst().orElse(null);
            return new JobItem(j.id(), j.branch().plannedTenantId(), j.tenantName(), j.branch().code(), j.status(), failed, steps);
        }).toList();
        int limit = entitlements.branchLimit();
        return new BranchList(items, jobs, limit == Integer.MAX_VALUE ? null : limit,
                repo.branchesInUse(owner.brandId(), owner.ownerEmail(), owner.brandName()));
    }

    @Transactional
    public Started start(UUID staffId, String ip, String name, String branchCode, String address, String timezone, UUID copyFromTenantId,
                         List<String> copy, String stepUpToken) {
        UUID tenant = tenantContext.current();
        OwnerLink owner = requireOwner(staffId);
        realDataGuard.check();
        repo.lockBrand(owner.brandId());
        // before the step-up, so an owner at the limit sees the upgrade link without confirming first
        if (repo.branchesInUse(owner.brandId(), owner.ownerEmail(), owner.brandName()) >= entitlements.branchLimit()) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "PLAN_LIMIT", "PLAN_LIMIT",
                    "Your plan's branches are all in use. Upgrade your plan to add another branch.", Map.of("upgradeUrl", UPGRADE_URL));
        }
        StepUpVerifier.StepUp su = stepUp.requireFor(stepUpToken, staffId, stepUpTarget(branchCode));
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "Validation failed", "timezone must be an IANA zone such as Asia/Kolkata.");
        }
        // a clinic of another owner answers like one that does not exist
        String sourceRegion = repo.regionOfOwnedClinic(owner.ownerId(), copyFromTenantId).orElseThrow(ApiException::notFound);
        if (!sourceRegion.equals(owner.region())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "REGION_MISMATCH", "REGION_MISMATCH",
                    "The branch to copy from is in another region. A branch always stays in your region.");
        }
        if (repo.branchCodeTaken(owner.brandId(), owner.ownerEmail(), owner.brandName(), branchCode)) {
            throw new ApiException(HttpStatus.CONFLICT, "BRANCH_CODE_TAKEN", "BRANCH_CODE_TAKEN", "Another of your branches already uses this code.");
        }
        UUID jobId = UUID.randomUUID();
        Branch branch = new Branch(copyFromTenantId, staffId, UUID.randomUUID(), branchCode, address.trim(), timezone,
                ConfigBundle.SECTIONS.stream().filter(copy::contains).toList());
        String slug = slug(owner.slug(), branchCode, jobId);
        if (!repo.insertBranchJob(jobId, branch, slug, name.trim(), owner.region(), owner.ownerEmail(), owner.ownerName(), owner.brandName(),
                su.assertionId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "STEP_UP_REUSED", "STEP_UP_REUSED", "This confirmation was already used. Confirm again.");
        }
        for (int i = 0; i < STEPS.size(); i++) {
            repo.insertStep(UUID.randomUUID(), jobId, STEPS.get(i), i + 1);
        }
        Map<String, Object> after = new LinkedHashMap<>(); // clinic facts only: no address, no owner contact
        after.put("branchTenantId", branch.plannedTenantId());
        after.put("branchCode", branchCode);
        after.put("region", owner.region());
        after.put("copyFromTenantId", copyFromTenantId);
        after.put("copy", branch.copySections());
        audit.record(tenant, "staff", staffId, owner.staffName(), "Owner", ip, "setup.branch_add_started", "provisioning_job", jobId, null, after);
        kickAfterCommit(jobId);
        return new Started(jobId, branch.plannedTenantId());
    }

    /** Runs the job on from the step that failed. The job must be one of this owner's own; anything else is a 404. */
    @Transactional
    public Started retry(UUID staffId, String ip, UUID jobId) {
        OwnerLink owner = requireOwner(staffId);
        Job job = repo.openBranchJobs(owner.ownerEmail()).stream().filter(j -> j.id().equals(jobId)).findFirst().orElseThrow(ApiException::notFound);
        if ("rolled_back".equals(job.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "JOB_TERMINAL", "JOB_TERMINAL", "This attempt was undone completely. Start adding the branch again.");
        }
        audit.record(tenantContext.current(), "staff", staffId, owner.staffName(), "Owner", ip, "setup.branch_add_retried", "provisioning_job", jobId,
                null, Map.of("status", job.status()));
        kickAfterCommit(jobId);
        return new Started(jobId, job.branch().plannedTenantId());
    }

    private OwnerLink requireOwner(UUID staffId) {
        return repo.ownerLink(tenantContext.current(), staffId).orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_OWNER", "NOT_OWNER",
                "Only the clinic owner can add or see branches."));
    }

    private void kickAfterCommit(UUID jobId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                worker.kick(jobId);
            }
        });
    }

    /** The sign-in code of the new clinic: the current clinic's code plus the branch code; a few job-id characters if that is taken. */
    private String slug(String currentSlug, String branchCode, UUID jobId) {
        String code = branchCode.toLowerCase(Locale.ROOT);
        String base = currentSlug.toLowerCase(Locale.ROOT);
        String slug = base.substring(0, Math.min(base.length(), 62 - code.length())) + "-" + code;
        return repo.slugTaken(slug) ? slug.substring(0, Math.min(slug.length(), 57)) + "-" + jobId.toString().substring(0, 5) : slug;
    }
}
