package com.nabd.hms.platform.provisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nabd.hms.common.EmailSender;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.WabaProvisioningGateway;
import com.nabd.hms.owner.OwnerService;
import com.nabd.hms.platform.tenant.TenantLifecycleService;
import com.nabd.hms.staff.StaffService;
import com.nabd.hms.staff.dto.StaffInviteRequest;
import com.nabd.hms.staff.dto.StaffInviteResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.platform.provisioning.ProvisioningModels.Job;
import static com.nabd.hms.platform.provisioning.ProvisioningModels.StepResult;

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
    private final StaffService staffService;
    private final EmailSender emailSender;
    private final String frontendBaseUrl;
    private final OwnerService ownerService;

    ProvisioningStepRunner(ProvisioningRepository repo, TenantContext tenantContext,
                            WabaProvisioningGateway wabaGateway, ObjectMapper objectMapper,
                            TenantLifecycleService lifecycleService, StaffService staffService,
                            EmailSender emailSender, @Value("${app.frontend-base-url}") String frontendBaseUrl,
                            OwnerService ownerService) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.wabaGateway = wabaGateway;
        this.objectMapper = objectMapper;
        this.lifecycleService = lifecycleService;
        this.staffService = staffService;
        this.emailSender = emailSender;
        this.frontendBaseUrl = frontendBaseUrl;
        this.ownerService = ownerService;
    }

    /** Returns both reveal-once invite tokens when this step is verify_invite_owner (StepResult.NONE otherwise, never persisted). */
    @Transactional
    StepResult run(Job job, String stepName) {
        return switch (stepName) {
            case "create_tenant" -> {
                createTenant(job);
                yield StepResult.NONE;
            }
            case "migrate_schema" -> {
                // No-op by design: tenancy here is shared-schema + RLS (app.tenant_id), not
                // schema-per-tenant, so there is no per-tenant schema to migrate — the global
                // Flyway migration already applied at boot covers every tenant, this one included.
                yield StepResult.NONE;
            }
            case "seed_masters" -> {
                seedMasters(job);
                yield StepResult.NONE;
            }
            case "provision_whatsapp" -> {
                wabaGateway.provisionNumber(job.tenantSlug());
                yield StepResult.NONE;
            }
            case "verify_invite_owner" -> inviteOwner(job);
            case "go_live" -> {
                goLive(job);
                yield StepResult.NONE;
            }
            default -> throw new IllegalStateException("unknown provisioning step " + stepName);
        };
    }

    /** Compensates a step that already committed 'done' in an earlier transaction. Called in reverse step order. */
    @Transactional
    void undo(Job job, String stepName) {
        switch (stepName) {
            case "seed_masters" -> undoSeedMasters(job);
            case "create_tenant" -> undoCreateTenant(job);
            case "verify_invite_owner" -> undoInviteOwner(job);
            case "migrate_schema", "provision_whatsapp" -> {
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

    private void undoInviteOwner(Job job) {
        UUID tenantId = job.createdTenantId();
        if (tenantId == null) {
            return;
        }
        tenantContext.set(tenantId);
        repo.deleteStaffByTenant(tenantId);
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
                fullGrant("staff"), fullGrant("patients"), fullGrant("queue"), fullGrant("setup"),
                fullGrant("clinical"), fullGrant("billing"), fullGrant("specialty_dental"),
                fullGrant("reports"), fullGrant("pharmacy"), fullGrant("packages"), fullGrant("nursing")));
        repo.insertBuiltInOwnerRole(tenantId, grantsJson);
    }

    /** NB-353: reuses StaffService's existing invite/accept machinery instead of inventing a second
     * temp-PIN mechanism — same "raw token relayed to the caller, only its hash persisted" shape
     * regular staff invites already use (see StaffInviteResponse's doc comment). NB-354 additionally
     * invites the owner's top-level account (separate from this per-tenant staff row) whenever they
     * don't have a PIN yet — OwnerService.invite() itself skips this for an owner who's already
     * activated (e.g. provisioning a second clinic for the same person). */
    private StepResult inviteOwner(Job job) {
        UUID tenantId = job.createdTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("verify_invite_owner ran before create_tenant recorded a tenant id");
        }
        tenantContext.set(tenantId);
        String ownerAccountInviteToken = ownerService.invite(job.createdOwnerId()).orElse(null);
        if (ownerAccountInviteToken != null) {
            String accountLink = frontendBaseUrl + "/owner/accept-invite/" + ownerAccountInviteToken;
            try {
                emailSender.send(job.ownerEmail(), "Set up your Nabd owner account",
                        "Hi " + job.ownerName() + ",\n\n" +
                                "Set a PIN for your Nabd owner account — this is separate from any one clinic's " +
                                "login and lets you switch between every clinic you own from one place, as you " +
                                "add more.\n\n" +
                                "Set your PIN here (link expires in 72 hours):\n" + accountLink + "\n\n" +
                                "If you weren't expecting this, you can ignore this email.");
            } catch (Exception e) {
                log.warn("owner account invite email failed to send to {} — falling back to manual relay",
                        job.ownerEmail(), e);
            }
        }

        if (repo.hasOwnerStaff(tenantId)) {
            // retry after a later step failed — this clinic's own staff invite already went out and
            // was already revealed once, but the owner-account invite above still needed evaluating
            // (a second clinic for an already-activated owner correctly sends nothing at all).
            return new StepResult(null, ownerAccountInviteToken);
        }
        UUID roleId = repo.findBuiltInRoleId(tenantId)
                .orElseThrow(() -> new IllegalStateException("verify_invite_owner ran before seed_masters created the Owner role"));
        StaffInviteResponse invite = staffService.invite(tenantId, null,
                new StaffInviteRequest(job.ownerEmail(), job.ownerName(), job.ownerMobile(), roleId, "all_clinic_patients"));
        String link = frontendBaseUrl + "/accept-invite/" + invite.inviteToken();
        try {
            emailSender.send(job.ownerEmail(), "You're invited to " + job.tenantName() + " on Nabd",
                    "Hi " + job.ownerName() + ",\n\n" +
                            "Your clinic \"" + job.tenantName() + "\" is ready on Nabd.\n\n" +
                            "Set your PIN and sign in here (link expires in 72 hours):\n" + link + "\n\n" +
                            "If you weren't expecting this, you can ignore this email.");
        } catch (Exception e) {
            // Best-effort: email is a convenience on top of the reveal-once token below, never a
            // requirement for it. Render blocks outbound SMTP on its network (confirmed by a live
            // timeout), so this catch is load-bearing today, not defensive-for-its-own-sake — without
            // it, a send failure would roll back the staff row this same @Transactional step just created.
            log.warn("owner invite email failed to send to {} for tenant {} — falling back to manual relay",
                    job.ownerEmail(), job.tenantSlug(), e);
        }
        // Still returned even though the email above may already have delivered it — same
        // reveal-once value shown as a manual-relay fallback if the email never arrives (unconfigured
        // SMTP, spam filter, wrong address typed at provisioning time).
        return new StepResult(invite.inviteToken(), ownerAccountInviteToken);
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
