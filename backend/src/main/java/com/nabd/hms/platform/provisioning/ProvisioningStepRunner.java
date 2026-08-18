package com.nabd.hms.platform.provisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.WabaProvisioningGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static com.nabd.hms.platform.provisioning.ProvisioningModels.Job;

/**
 * Executes exactly one provisioning step, in its own transaction, called through the Spring proxy
 * from {@link ProvisioningService} (never self-invoked — a self-call would silently skip @Transactional).
 * A step that fails rolls back everything it did this attempt — no half-created owner/brand/tenant
 * row survives a failed create_tenant step, which is also most of what NB-259's "no half-created
 * tenant" rollback guarantee needs; NB-259 still owns marking the *job* rolled_back on top of this.
 */
@Service
class ProvisioningStepRunner {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningStepRunner.class);

    private final ProvisioningRepository repo;
    private final TenantContext tenantContext;
    private final WabaProvisioningGateway wabaGateway;
    private final ObjectMapper objectMapper;

    ProvisioningStepRunner(ProvisioningRepository repo, TenantContext tenantContext,
                            WabaProvisioningGateway wabaGateway, ObjectMapper objectMapper) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.wabaGateway = wabaGateway;
        this.objectMapper = objectMapper;
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
            case "go_live" -> {
                // Final checkpoint only: the tenant has been usable in 'trial' status since
                // create_tenant. Promoting past 'trial' is NB-261's tenant-lifecycle job, not this one's.
            }
            default -> throw new IllegalStateException("unknown provisioning step " + stepName);
        }
    }

    private void createTenant(Job job) {
        UUID ownerId = repo.findOwnerByEmail(job.ownerEmail())
                .orElseGet(() -> repo.insertOwner(job.ownerName(), job.ownerEmail()));
        UUID brandId = repo.findBrandByOwnerAndName(ownerId, job.brandName())
                .orElseGet(() -> repo.insertBrand(ownerId, job.brandName()));
        UUID tenantId = repo.insertTenant(job.tenantSlug(), job.tenantName(), job.region(), brandId);
        repo.setJobCreatedRefs(job.id(), tenantId, ownerId, brandId);
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
