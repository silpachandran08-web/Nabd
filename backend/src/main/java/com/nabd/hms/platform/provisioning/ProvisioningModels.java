package com.nabd.hms.platform.provisioning;

import java.util.List;

import java.time.Instant;
import java.util.UUID;

final class ProvisioningModels {

    private ProvisioningModels() {
    }

    record Job(UUID id, UUID requestedBy, String tenantSlug, String tenantName, String region,
               String ownerEmail, String ownerName, String ownerMobile, String brandName, String status, String path,
               UUID createdTenantId, UUID createdOwnerId, UUID createdBrandId,
               boolean ownerNewlyCreated, boolean brandNewlyCreated,
               Instant approvedAt, UUID approvedBy) {

        boolean isGatedAndUnapproved() {
            return "enterprise".equals(path) && approvedAt == null;
        }
    }

    record JobStep(UUID id, UUID jobId, String stepName, int stepOrder, String status,
                   Instant startedAt, Instant completedAt, String errorDetail) {
    }

    /** Only verify_invite_owner ever populates either field — every other step yields NONE. Two
     * separate invites now exist for the same real-world owner: the per-tenant staff row (accept at
     * /accept-invite) and the top-level Owner account PIN (accept at /owner/accept-invite) that lets
     * the same person log into every clinic they own from one place (NB-350). */
    record StepResult(String staffInviteToken, String ownerAccountInviteToken, List<Email> emails) {
        static final StepResult NONE = new StepResult(null, null, List.of());
    }

    /** OPS-012: an email a step wants sent. The step only returns it; ProvisioningService sends it after the step's
     * transaction committed, so a slow or blocked SMTP server never holds a DB connection. */
    record Email(String to, String subject, String body, String what) {
    }
}
