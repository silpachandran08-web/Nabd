package com.nabd.hms.platform.provisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.WabaProvisioningGateway;
import com.nabd.hms.platform.tenant.TenantLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.platform.provisioning.ProvisioningModels.Job;

/**
 * Executes exactly one provisioning step, in its own transaction, called through the Spring proxy
 * from {@link ProvisioningService} (never self-invoked — a self-call would silently skip @Transactional).
 * A step that fails rolls back everything it did this attempt — no half-created owner/brand/tenant
 * row survives a failed create_tenant step. {@link #undo} is the separate, job-level compensation
 * NB-259 adds on top: when a LATER step fails fatally, it undoes the effects of every step that had
 * already committed in an earlier, separate transaction.
 */
@Service
class ProvisioningStepRunner {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningStepRunner.class);

    private final ProvisioningRepository repo;
    private final TenantContext tenantContext;
    private final WabaProvisioningGateway wabaGateway;
    private final ObjectMapper objectMapper;
    private final TenantLifecycleService lifecycleService;

    ProvisioningStepRunner(ProvisioningRepository repo, TenantContext tenantContext,
                            WabaProvisioningGateway wabaGateway, ObjectMapper objectMapper,
                            TenantLifecycleService lifecycleService) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.wabaGateway = wabaGateway;
        this.objectMapper = objectMapper;
        this.lifecycleService = lifecycleService;
    }

    @Transactional
    void run(Job job, String stepName) {
        switch (stepName) {
            case "create_tenant" -> createTenant(job);
            case "migrate_schema" -> {
                // No-op by design: tenancy here is shared-schema + RLS (app.tenant_id), not
                // schema-per-tenant, so there is no per-tenant schema to migrate — the global
                // Flyway migration already applied at boot covers every tenant, this one included.
            }
            case "seed_masters" -> seedMasters(job);
            case "provision_whatsapp" -> wabaGateway.provisionNumber(job.tenantSlug());
            case "verify_invite_owner" -> inviteOwner(job);
            case "go_live" -> goLive(job);
            default -> throw new IllegalStateException("unknown provisioning step " + stepName);
        }
    }

    /** Compensates a step that already committed 'done' in an earlier transaction. Called in reverse step order. */
    @Transactional
    void undo(Job job, String stepName) {
        switch (stepName) {
            case "seed_masters" -> undoSeedMasters(job);
            case "create_tenant" -> undoCreateTenant(job);
            case "migrate_schema", "provision_whatsapp", "verify_invite_owner" -> {
                // nothing persisted by run() for these steps — see the comments there
            }
            case "go_live" -> {
                // go_live is structurally the last step — nothing can fail after it to trigger an
                // undo of it, and its own transition() call already self-rolls-back on failure like
                // every other step. tenant_lifecycle_events also cascades on tenant deletion (V11),
                // so undoCreateTenant deleting the tenant can never hit an FK violation from this.
            }
            default -> throw new IllegalStateException("unknown provisioning step " + stepName);
        }
    }

    private void undoSeedMasters(Job job) {
        UUID tenantId = job.createdTenantId();
        if (tenantId == null) {
            return;
        }
        tenantContext.set(tenantId);
        repo.deleteBuiltInRole(tenantId);
    }

    /** Reverse FK order of createTenant: tenant (child of brand) before brand (child of owner) before owner. */
    private void undoCreateTenant(Job job) {
        // Clear the job's own FKs first — provisioning_jobs.created_*_id references these rows,
        // so they can't be deleted while this job still points at them.
        repo.setJobCreatedRefs(job.id(), null, null, null);
        if (job.createdTenantId() != null) {
            repo.deleteTenant(job.createdTenantId());
        }
        if (job.brandNewlyCreated() && job.createdBrandId() != null) {
            repo.deleteBrand(job.createdBrandId());
        }
        if (job.ownerNewlyCreated() && job.createdOwnerId() != null) {
            repo.deleteOwner(job.createdOwnerId());
        }
        log.info("provisioning job {} rolled back create_tenant (tenant {}, owner-newly-created={}, brand-newly-created={})",
                job.id(), job.createdTenantId(), job.ownerNewlyCreated(), job.brandNewlyCreated());
    }

    private void createTenant(Job job) {
        Optional<UUID> existingOwner = repo.findOwnerByEmail(job.ownerEmail());
        boolean ownerIsNew = existingOwner.isEmpty();
        UUID ownerId = existingOwner.orElseGet(() -> repo.insertOwner(job.ownerName(), job.ownerEmail()));

        // NB-002's region lock is irreversible and owner-wide: an owner's first clinic sets the
        // region, every clinic after that must match. A brand-new owner has no prior region to
        // conflict with. This must fail before anything is inserted, not after — see FatalProvisioningException.
        if (!ownerIsNew) {
            repo.findAnyRegionForOwner(ownerId).ifPresent(existingRegion -> {
                if (!existingRegion.equals(job.region())) {
                    throw new FatalProvisioningException("Owner already has a clinic in region " + existingRegion +
                            "; cannot provision a new clinic in region " + job.region() + " for the same owner.");
                }
            });
        }

        Optional<UUID> existingBrand = repo.findBrandByOwnerAndName(ownerId, job.brandName());
        boolean brandIsNew = existingBrand.isEmpty();
        UUID brandId = existingBrand.orElseGet(() -> repo.insertBrand(ownerId, job.brandName()));

        UUID tenantId = repo.insertTenant(job.tenantSlug(), job.tenantName(), job.region(), brandId);
        repo.setJobCreatedRefs(job.id(), tenantId, ownerId, brandId);
        repo.setJobCreationFlags(job.id(), ownerIsNew, brandIsNew);
        log.info("provisioning job {} created tenant {} (owner {}, brand {})", job.id(), tenantId, ownerId, brandId);
    }

    /** Reuses ApiTestBase's proven full-access shape — every module the Owner shadow-staff path (OwnerRepository) needs. */
    private void seedMasters(Job job) {
        UUID tenantId = job.createdTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("seed_masters ran before create_tenant recorded a tenant id");
        }
        // roles is RLS-protected — app.tenant_id must be set before ANY query against it, including
        // the idempotency check below, or the bare tenant_isolation policy cast ('' ::uuid) throws.
        tenantContext.set(tenantId);
        if (repo.hasBuiltInRole(tenantId)) {
            return; // retry after a later step failed — already seeded, nothing to redo
        }
        String grantsJson = writeGrantsJson(List.of(
                fullGrant("staff"), fullGrant("patients"), fullGrant("queue")));
        repo.insertBuiltInOwnerRole(tenantId, grantsJson);
    }

    private void inviteOwner(Job job) {
        // ponytail: logs instead of sending — no owner-invite channel (email/WhatsApp) built yet.
        log.info("[MOCK OWNER INVITE] would invite {} <{}> to tenant {}", job.ownerName(), job.ownerEmail(), job.tenantSlug());
    }

    private void goLive(Job job) {
        UUID tenantId = job.createdTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("go_live ran before create_tenant recorded a tenant id");
        }
        lifecycleService.transition(tenantId, "trialing", job.requestedBy(), "provisioning completed");
    }

    private static ModuleGrant fullGrant(String module) {
        return new ModuleGrant(module, true, true, true, true, true, true, true);
    }

    private String writeGrantsJson(List<ModuleGrant> grants) {
        try {
            return objectMapper.writeValueAsString(grants);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize role grants", e);
        }
    }
}
