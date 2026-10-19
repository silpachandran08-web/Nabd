package com.nabd.hms.platform.access;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.platform.access.dto.GrantResponse;
import com.nabd.hms.platform.access.dto.PatientViewResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.nabd.hms.platform.access.SupportAccessModels.Grant;
import static com.nabd.hms.platform.access.SupportAccessModels.OperatorInfo;
import static com.nabd.hms.platform.access.SupportAccessModels.RedactedPatient;

/**
 * SSA-04: a support engineer never sees clinical fields, using the same overlay the clinic sees.
 * Access is consented, time-boxed and audited. "Consented" here is the operator's own logged,
 * reason-stated request (SSA-02's hard rule list doesn't specify a tenant-side approval step, and
 * no tenant/owner dashboard exists yet to host one — same gap NB-257 had before this session
 * fixed it on the platform side); every grant and every read through it is still fully audited via
 * AuditService (NB-238), which is what makes "no silent impersonation" true regardless.
 */
@Service
public class SupportAccessService {

    private static final Logger log = LoggerFactory.getLogger(SupportAccessService.class);
    static final int MAX_MINUTES = 60; // matches the wireframe's stated window; PLT-056 makes it a ceiling
    static final Set<String> SCOPES = Set.of("config_only", "redacted_patient_view");

    private final SupportAccessRepository repo;
    private final AuditService auditService;
    private final TenantContext tenantContext;

    SupportAccessService(SupportAccessRepository repo, AuditService auditService, TenantContext tenantContext) {
        this.repo = repo;
        this.auditService = auditService;
        this.tenantContext = tenantContext;
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
        OperatorInfo operator = operatorInfo(operatorId);
        UUID grantId = UUID.randomUUID();
        repo.insert(grantId, tenantId, ticketId, scope, operatorId, reason, mins);
        auditService.record(tenantId, "operator", operatorId, operator.name(), operator.role(), ipAddress,
                "support_access.grant", "tenant", tenantId, null,
                Map.of("reason", reason, "ticketId", ticketId.toString(), "scope", scope, "minutes", mins));
        log.info("support access grant {} for tenant {} by operator {} ({})",
                grantId, tenantId, operatorId, operator.role());
        return toResponse(repo.findById(grantId).orElseThrow(), operator);
    }

    private static ApiException badRequest(String slug, String title, String code, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, slug, title, detail, Map.of("code", code));
    }

    public List<GrantResponse> listGrants() {
        return repo.listAll().stream()
                .map(g -> toResponse(g, operatorInfo(g.operatorId())))
                .toList();
    }

    @Transactional
    public void revoke(UUID grantId, UUID callerOperatorId, String ipAddress) {
        Grant grant = repo.findById(grantId).orElseThrow(SupportAccessService::notFound);
        if (grant.revokedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "already-revoked", "Already revoked",
                    "This support access grant was already revoked.");
        }
        repo.revoke(grantId);
        OperatorInfo caller = operatorInfo(callerOperatorId);
        auditService.record(grant.tenantId(), "operator", callerOperatorId, caller.name(), caller.role(),
                ipAddress, "support_access.revoke", "tenant", grant.tenantId(), null, null);
        log.info("support access grant {} revoked by operator {}", grantId, callerOperatorId);
    }

    /** The redacted overlay itself — every call is audited, and only the grant's own operator may use it. */
    @Transactional
    public PatientViewResponse viewPatient(UUID grantId, UUID patientId, UUID callerOperatorId, String ipAddress) {
        Grant grant = repo.findById(grantId).orElseThrow(SupportAccessService::notFound);
        if (!grant.operatorId().equals(callerOperatorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "not-your-grant", "Not your grant",
                    "This support access grant belongs to a different operator.");
        }
        if (!grant.isActive(Instant.now())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "grant-not-active", "Grant not active",
                    "This support access grant has expired or been revoked.");
        }
        if (!"redacted_patient_view".equals(grant.scope())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "scope-not-permitted", "Outside grant scope",
                    "This support access grant is config_only and does not open patient records.");
        }

        tenantContext.set(grant.tenantId());
        RedactedPatient patient = repo.findRedactedPatient(grant.tenantId(), patientId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested patient was not found."));

        OperatorInfo operator = operatorInfo(callerOperatorId);
        auditService.record(grant.tenantId(), "operator", callerOperatorId, operator.name(), operator.role(),
                ipAddress, "patient.support_view", "patient", patientId, null, null);

        return new PatientViewResponse(patient.id(), patient.mrn(), patient.name(), patient.phone(),
                patient.dob(), patient.gender(), patient.status());
    }

    private OperatorInfo operatorInfo(UUID operatorId) {
        return repo.findOperatorInfo(operatorId).orElseThrow(SupportAccessService::notFound);
    }

    private GrantResponse toResponse(Grant g, OperatorInfo operator) {
        return new GrantResponse(g.id(), g.tenantId(), g.ticketId(), g.scope(), g.operatorId(), operator.name(), operator.role(),
                g.reason(), g.grantedAt(), g.expiresAt(), g.revokedAt(), g.isActive(Instant.now()));
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }
}
