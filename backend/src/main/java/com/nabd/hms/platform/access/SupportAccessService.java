package com.nabd.hms.platform.access;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.platform.access.dto.DecisionRequest;
import com.nabd.hms.platform.access.dto.DecisionResponse;
import com.nabd.hms.platform.access.dto.GrantResponse;
import com.nabd.hms.platform.access.dto.OwnerRequestResponse;
import com.nabd.hms.platform.access.dto.PatientViewResponse;
import com.nabd.hms.platform.ticket.TicketPhiRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.nabd.hms.platform.access.SupportAccessModels.Grant;
import static com.nabd.hms.platform.access.SupportAccessModels.OperatorInfo;
import static com.nabd.hms.platform.access.SupportAccessModels.RedactedPatient;

/**
 * PLT-057 (SSA-04): support access is requested by an operator, decided by the clinic owner, and only then open.
 * requested --owner approves--> active (window starts at approval, at most 60 minutes) --time runs out--> expired.
 * requested --owner declines--> declined; requested --24 h pass--> expired; requested/active --operator--> revoked.
 * The state is a single compare-and-set per transition (SupportAccessRepository), and every use of a grant re-checks
 * status and window, so the sweep job (SupportAccessExpiryJob) only writes the audit rows and events; it is not what
 * closes the door. Every step lands in the clinic's own audit_log, and the redacted patient view returns no clinical field.
 */
@Service
public class SupportAccessService {

    private static final Logger log = LoggerFactory.getLogger(SupportAccessService.class);
    static final int MAX_MINUTES = 60; // matches the wireframe's stated window; PLT-056 makes it a ceiling
    static final Set<String> SCOPES = Set.of("config_only", "redacted_patient_view");
    static final Set<String> STATUSES = Set.of("requested", "active", "declined", "expired", "revoked");
    static final String REQUESTED_EVENT = "platform.support_access.requested.v1";
    static final String DECIDED_EVENT = "platform.support_access.decided.v1";

    private final SupportAccessRepository repo;
    private final AuditService auditService;
    private final TenantContext tenantContext;
    private final EventPublisher events;
    private final ActorDirectory actors;

    private final Clock clock;

    SupportAccessService(SupportAccessRepository repo, AuditService auditService, TenantContext tenantContext, Clock clock,
                         EventPublisher events, ActorDirectory actors) {
        this.clock = clock;
        this.repo = repo;
        this.auditService = auditService;
        this.tenantContext = tenantContext;
        this.events = events;
        this.actors = actors;
    }

    @Transactional
    public GrantResponse requestGrant(UUID tenantId, UUID ticketId, String scope, Integer minutes, UUID operatorId,
                                      String reason, String ipAddress) {
        if (ticketId == null) {
            throw badRequest("ticket-required", "Ticket required", "TICKET_REQUIRED",
                    "A support grant must name the support ticket it serves.");
        }
        if (scope == null || !SCOPES.contains(scope)) {
            throw badRequest("scope-invalid", "Scope invalid", "SCOPE_INVALID",
                    "scope must be config_only or redacted_patient_view.");
        }
        int mins = minutes == null ? MAX_MINUTES : minutes;
        if (mins < 1 || mins > MAX_MINUTES) {
            throw badRequest("duration-out-of-range", "Duration out of range", "DURATION_OUT_OF_RANGE",
                    "minutes must be between 1 and " + MAX_MINUTES + ".");
        }
        if (!repo.ticketBelongsToTenant(ticketId, tenantId)) {
            throw badRequest("ticket-invalid", "Ticket invalid", "TICKET_INVALID",
                    "No support ticket with this id exists for this clinic.");
        }
        if (repo.hasOpenRequest(tenantId, ticketId, operatorId)) {
            throw openRequest();
        }
        OperatorInfo operator = operatorInfo(operatorId);
        // the reason goes to the owner's WhatsApp and the clinic's audit log: phone numbers, emails and IDs never do
        String safeReason = TicketPhiRedactor.redact(reason).text();
        UUID grantId = UUID.randomUUID();
        try {
            repo.insert(grantId, tenantId, ticketId, scope, operatorId, safeReason, mins);
        } catch (DuplicateKeyException e) { // two requests raced past hasOpenRequest; the unique index decides
            throw openRequest();
        }
        auditService.record(tenantId, "operator", operatorId, operator.name(), operator.role(), ipAddress,
                "support_access.requested", "tenant", tenantId, null,
                Map.of("grantId", grantId.toString(), "reason", safeReason, "ticketId", ticketId.toString(), "scope", scope, "minutes", mins));
        events.publish(tenantId, REQUESTED_EVENT, Map.of("tenantId", tenantId.toString(), "grantId", grantId.toString(),
                "operatorName", operator.name(), "scope", scope, "minutes", mins, "reason", safeReason));
        log.info("support access request {} for tenant {} by operator {} ({})", grantId, tenantId, operatorId, operator.role());
        return toResponse(repo.findById(grantId).orElseThrow(), operator);
    }

    private static ApiException openRequest() {
        return new ApiException(HttpStatus.CONFLICT, "request-already-open", "Request already open",
                "This operator already has an open access request for this ticket.", Map.of("code", "REQUEST_ALREADY_OPEN"));
    }

    private static ApiException badRequest(String slug, String title, String code, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, slug, title, detail, Map.of("code", code));
    }

    public List<GrantResponse> listGrants() {
        return repo.listAll().stream()
                .map(g -> toResponse(g, operatorInfo(g.operatorId())))
                .toList();
    }

    /** Owner side: this clinic's requests, newest first. The clinic is the bound tenant, never a parameter. */
    @Transactional(readOnly = true)
    public OwnerRequestResponse listRequests(String status, int limit) {
        if (status != null && !STATUSES.contains(status)) {
            throw badRequest("status-invalid", "Status invalid", "STATUS_INVALID",
                    "status must be one of requested, active, declined, expired, revoked.");
        }
        Instant now = Instant.now(clock);
        List<OwnerRequestResponse.Item> items = repo.listForTenant(tenantContext.current(), 100).stream()
                .filter(g -> status == null || status.equals(g.effectiveStatus(now)))
                .limit(limit)
                .map(g -> new OwnerRequestResponse.Item(g.id(), operatorInfo(g.operatorId()).name(), g.reason(), g.scope(),
                        g.minutes(), g.effectiveStatus(now), g.grantedAt(), g.expiresAt(), g.windowStartedAt(),
                        g.windowEndsAt(), g.declineNote()))
                .toList();
        return new OwnerRequestResponse(items);
    }

    /** Owner approves or declines. The window starts now, on approval, and not before. */
    @Transactional
    public DecisionResponse decide(UUID id, UUID staffId, DecisionRequest req, String ipAddress) {
        UUID tenantId = tenantContext.current();
        boolean approve = "approve".equals(req.decision());
        ActorDirectory.Actor actor = actors.findActor(tenantId, staffId).orElseThrow(SupportAccessService::notFound);
        Optional<Grant> decided = repo.decide(id, tenantId, approve, staffId, approve ? null : req.note());
        if (decided.isEmpty()) {
            Grant current = repo.findForTenant(id, tenantId).orElseThrow(SupportAccessService::notFound); // another clinic's id is a 404
            if ("requested".equals(current.status()) || "expired".equals(current.status())) {
                throw new ApiException(HttpStatus.GONE, "expired", "Request expired",
                        "This access request was not answered in time and has lapsed.", Map.of("code", "EXPIRED"));
            }
            throw new ApiException(HttpStatus.CONFLICT, "already-decided", "Already decided",
                    "This access request has already been decided.", Map.of("code", "ALREADY_DECIDED"));
        }
        Grant g = decided.get();
        OperatorInfo operator = operatorInfo(g.operatorId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("grantId", g.id().toString());
        after.put("operator", operator.name());
        after.put("scope", g.scope());
        if (approve) {
            after.put("minutes", g.minutes());
            after.put("windowEndsAt", g.windowEndsAt().toString());
        }
        auditService.record(tenantId, "staff", staffId, actor.name(), actor.role(), ipAddress,
                approve ? "support_access.session_started" : "support_access.declined", "tenant", tenantId, null, after);
        events.publish(tenantId, DECIDED_EVENT, Map.of("tenantId", tenantId.toString(), "grantId", g.id().toString(),
                "decision", approve ? "approved" : "declined"));
        log.info("support access {} {} by staff {}", g.id(), approve ? "approved" : "declined", staffId);
        return new DecisionResponse(g.status(), g.windowEndsAt());
    }

    @Transactional
    public void revoke(UUID grantId, UUID callerOperatorId, String ipAddress) {
        Grant grant = repo.findById(grantId).orElseThrow(SupportAccessService::notFound);
        if (repo.revoke(grantId) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "already-revoked", "Already revoked",
                    "This support access grant was already revoked or is over.");
        }
        OperatorInfo caller = operatorInfo(callerOperatorId);
        auditService.record(grant.tenantId(), "operator", callerOperatorId, caller.name(), caller.role(),
                ipAddress, "support_access.revoke", "tenant", grant.tenantId(), null,
                Map.of("grantId", grantId.toString(), "was", grant.status()));
        if ("active".equals(grant.status())) {
            auditService.record(grant.tenantId(), "operator", callerOperatorId, caller.name(), caller.role(), ipAddress,
                    "support_access.session_ended", "tenant", grant.tenantId(), null,
                    Map.of("grantId", grantId.toString(), "reason", "revoked"));
        }
        log.info("support access grant {} revoked by operator {}", grantId, callerOperatorId);
    }

    /** The redacted overlay itself: initials, masked phone, birth year, MRN. Every call is audited, and only the grant's
     * own operator may use it, and only while the owner-approved window is open. */
    @Transactional
    public PatientViewResponse viewPatient(UUID grantId, UUID patientId, UUID callerOperatorId, String ipAddress) {
        Grant grant = repo.findById(grantId).orElseThrow(SupportAccessService::notFound);
        if (!grant.operatorId().equals(callerOperatorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "not-your-grant", "Not your grant",
                    "This support access grant belongs to a different operator.");
        }
        if (!"redacted_patient_view".equals(grant.scope())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "scope-not-permitted", "Outside grant scope",
                    "This support access grant is config_only and does not open patient records.");
        }

        if (!grant.isActive(Instant.now(clock))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "grant-not-active", "Grant not active",
                    "This support access grant is not open: it is waiting for the owner, or it was declined, has expired or was revoked.",
                    Map.of("code", "GRANT_NOT_ACTIVE"));
        }
        tenantContext.set(grant.tenantId());
        RedactedPatient patient = repo.findRedactedPatient(grant.tenantId(), patientId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested patient was not found."));

        OperatorInfo operator = operatorInfo(callerOperatorId);
        auditService.record(grant.tenantId(), "operator", callerOperatorId, operator.name(), operator.role(),
                ipAddress, "patient.support_view", "patient", patientId, null, Map.of("grantId", grantId.toString()));

        return new PatientViewResponse(PatientMasking.initials(patient.name()), PatientMasking.maskPhone(patient.phone()),
                patient.dob() == null ? null : patient.dob().getYear(), patient.mrn());
    }

    /** Sweep job, step 1: requests past 24 h and windows past their end. */
    @Transactional(readOnly = true)
    public List<Due> dueGrants() {
        return repo.due(100).stream().map(g -> new Due(g.id(), g.tenantId())).toList();
    }

    public record Due(UUID id, UUID clinic) {
    }

    /** Sweep job, step 2 (inside TenantScope for the grant's clinic): closes one grant and tells the owner's audit log. */
    @Transactional
    public void expire(UUID grantId) {
        Grant g = repo.findById(grantId).orElseThrow(SupportAccessService::notFound);
        Optional<String> was = repo.expire(grantId);
        if (was.isEmpty()) {
            return; // somebody else closed it first
        }
        boolean window = "active".equals(was.get());
        auditService.record(g.tenantId(), "system", null, "Nabd", "System", null,
                window ? "support_access.session_ended" : "support_access.request_expired", "tenant", g.tenantId(), null,
                Map.of("grantId", grantId.toString(), "reason", "expired"));
        events.publish(g.tenantId(), DECIDED_EVENT, Map.of("tenantId", g.tenantId().toString(), "grantId", grantId.toString(),
                "decision", "expired"));
    }

    private OperatorInfo operatorInfo(UUID operatorId) {
        return repo.findOperatorInfo(operatorId).orElseThrow(SupportAccessService::notFound);
    }

    private GrantResponse toResponse(Grant g, OperatorInfo operator) {
        Instant now = Instant.now(clock);
        return new GrantResponse(g.id(), g.tenantId(), g.ticketId(), g.scope(), g.operatorId(), operator.name(), operator.role(),
                g.reason(), g.grantedAt(), g.expiresAt(), g.revokedAt(), g.isActive(now), g.effectiveStatus(now), g.minutes(),
                g.windowStartedAt(), g.windowEndsAt(), g.declineNote());
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }
}
