package com.nabd.hms.auth;

import com.nabd.hms.auth.dto.BreakGlassReadsResponse;
import com.nabd.hms.auth.dto.BreakGlassReviewQueueResponse;
import com.nabd.hms.auth.dto.BreakGlassReviewRequest;
import com.nabd.hms.auth.dto.BreakGlassReviewResponse;
import com.nabd.hms.auth.internal.breakglass.BreakGlassPolicy;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** PLT-013: the owner's review of every ended break-glass grant (queue, what was read, outcome) and the one 24 h reminder.
 * The tenant is bound from the caller's token at transaction start (no TenantContext.set here); the job uses TenantScope. */
@Service
public class BreakGlassReviewService {

    static final String REMINDER = "review_reminder";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd MMM HH:mm");

    private final BreakGlassRepository repo;
    private final AuditService auditService;
    private final EventPublisher publisher;
    private final ClinicClock clinicClock;
    private final Clock clock;

    BreakGlassReviewService(BreakGlassRepository repo, AuditService auditService, EventPublisher publisher, ClinicClock clinicClock, Clock clock) {
        this.repo = repo;
        this.auditService = auditService;
        this.publisher = publisher;
        this.clinicClock = clinicClock;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BreakGlassReviewQueueResponse queue(UUID tenantId, String status, String cursor, int limit) {
        boolean reviewed;
        if (status == null || status.equals("unreviewed")) {
            reviewed = false;
        } else if (status.equals("reviewed")) {
            reviewed = true;
        } else {
            throw badRequest("status must be unreviewed or reviewed.");
        }
        Instant afterAt = null;
        UUID afterId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] p = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", 2);
                afterAt = Instant.parse(p[0]);
                afterId = UUID.fromString(p[1]);
            } catch (RuntimeException e) {
                throw badRequest("Invalid cursor.");
            }
        }
        List<BreakGlassReviewQueueResponse.Item> rows = repo.reviewQueue(tenantId, reviewed, afterAt, afterId, limit + 1);
        String next = null;
        if (rows.size() > limit) {
            rows = rows.subList(0, limit);
            BreakGlassReviewQueueResponse.Item last = rows.get(limit - 1);
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (last.startedAt() + "|" + last.grantId()).getBytes(StandardCharsets.UTF_8));
        }
        return new BreakGlassReviewQueueResponse(rows, next);
    }

    @Transactional(readOnly = true)
    public BreakGlassReadsResponse reads(UUID tenantId, UUID grantId) {
        repo.reviewTarget(tenantId, grantId).orElseThrow(ApiException::notFound); // another clinic's grant is a 404
        return new BreakGlassReadsResponse(repo.reads(tenantId, grantId));
    }

    @Transactional
    public BreakGlassReviewResponse review(Jwt caller, UUID grantId, BreakGlassReviewRequest req, String ip) {
        UUID tenantId = UUID.fromString(caller.getClaimAsString("tenantId"));
        UUID reviewerId = UUID.fromString(caller.getSubject());
        boolean notJustified = "not_justified".equals(req.outcome());
        boolean revoke = Boolean.TRUE.equals(req.revokePermission());
        String note = req.note() == null || req.note().isBlank() ? null : req.note().strip();
        if (notJustified && note == null) {
            throw badRequest("Write an incident note when the access was not justified.");
        }
        if (revoke && !notJustified) {
            throw badRequest("Emergency access can only be removed when the use was not justified.");
        }
        List<String> perms = caller.getClaimAsStringList("permissions");
        if (revoke && (perms == null || !perms.contains("staff:edit"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "Removing emergency access needs staff:edit.");
        }
        BreakGlassRepository.ReviewTarget target = repo.reviewTarget(tenantId, grantId).orElseThrow(ApiException::notFound);
        if (target.reviewed()) {
            throw alreadyReviewed();
        }
        if (!target.ended()) {
            throw new ApiException(HttpStatus.CONFLICT, "grant-active", "Emergency access still active",
                    "Review this emergency access after it has ended.", Map.of("code", "GRANT_ACTIVE"));
        }
        Instant reviewedAt = repo.markReviewed(tenantId, grantId, reviewerId, req.outcome(), note).orElseThrow(BreakGlassReviewService::alreadyReviewed);

        String name = caller.getClaimAsString("staffName");
        String role = caller.getClaimAsString("roleName");
        // the note is free text (may name a condition): stored on the grant, never in audit rows, events or logs
        auditService.record(tenantId, "staff", reviewerId, name, role, ip, "auth.break_glass_reviewed", "break_glass_grant", grantId, null,
                Map.of("outcome", req.outcome(), "permissionRevoked", revoke));
        if (notJustified) {
            auditService.record(tenantId, "staff", reviewerId, name, role, ip, "auth.break_glass_incident_opened", "break_glass_grant", grantId,
                    null, Map.of("staffId", target.staffId().toString()));
        }
        if (revoke) {
            repo.removeBreakGlass(tenantId, target.staffId());
            auditService.record(tenantId, "staff", reviewerId, name, role, ip, "staff.break_glass_removed", "staff", target.staffId(), null,
                    Map.of("grantId", grantId.toString()));
        }
        publisher.publish(tenantId, DomainEventType.BREAK_GLASS_REVIEWED, grantId, Map.of("grantId", grantId.toString(), "outcome", req.outcome()));
        return new BreakGlassReviewResponse(reviewedAt, revoke);
    }

    /** Reminder job, step 1: grants still unreviewed 24 h after they ended (all clinics, ids only). */
    @Transactional(readOnly = true)
    public List<BreakGlassRepository.Due> dueReminders() {
        return repo.dueReminders(Instant.now(clock));
    }

    /** Reminder job, step 2, in the grant's tenant transaction: stamp it, one in-app alert per owner, and the owner WhatsApp
     * through the PLT-012 outbox consumer (its own retries; a missing template never costs the in-app alert). */
    @Transactional
    public void remind(UUID tenantId, UUID grantId) {
        BreakGlassRepository.Reminder r = repo.stampReminder(tenantId, grantId).orElse(null);
        if (r == null) {
            return;
        }
        String message = "Emergency access by " + repo.staffName(r.staffId()) + " on "
                + FMT.format(r.activatedAt().atZone(clinicClock.zone(tenantId))) + " is still waiting for your review in Compliance.";
        for (BreakGlassRepository.Owner o : repo.owners(tenantId)) {
            repo.insertAlert(tenantId, grantId, o.id(), message, REMINDER);
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("tenantId", tenantId.toString());
        payload.put("grantId", grantId.toString());
        payload.put("staffId", r.staffId().toString());
        payload.put("activatedAt", r.activatedAt().toString());
        payload.put("reminder", true);
        publisher.publish(tenantId, BreakGlassPolicy.OWNER_WHATSAPP_EVENT, payload);
    }

    private static ApiException alreadyReviewed() {
        return new ApiException(HttpStatus.CONFLICT, "already-reviewed", "Already reviewed",
                "This emergency access has already been reviewed.", Map.of("code", "ALREADY_REVIEWED"));
    }

    private static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "bad-request", "Bad request", detail);
    }
}
