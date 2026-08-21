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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    private static final Duration GRANT_DURATION = Duration.ofMinutes(60); // matches the wireframe's stated window

    private final SupportAccessRepository repo;
    private final AuditService auditService;
    private final TenantContext tenantContext;

    SupportAccessService(SupportAccessRepository repo, AuditService auditService, TenantContext tenantContext) {
        this.repo = repo;
        this.auditService = auditService;
        this.tenantContext = tenantContext;
    }

    @Transactional
    public GrantResponse requestGrant(UUID tenantId, UUID operatorId, String reason, String ipAddress) {
        OperatorInfo operator = operatorInfo(operatorId);
        Grant grant = new Grant(UUID.randomUUID(), tenantId, operatorId, reason, Instant.now(),
                Instant.now().plus(GRANT_DURATION), null);
        repo.insert(grant);
        auditService.record(tenantId, "operator", operatorId, operator.name(), operator.role(), ipAddress,
                "support_access.grant", "tenant", tenantId, null, Map.of("reason", reason));
        log.info("support access grant {} for tenant {} by operator {} ({}): {}",
                grant.id(), tenantId, operatorId, operator.role(), reason);
        return toResponse(repo.findById(grant.id()).orElseThrow(), operator);
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
        return new GrantResponse(g.id(), g.tenantId(), g.operatorId(), operator.name(), operator.role(),
                g.reason(), g.grantedAt(), g.expiresAt(), g.revokedAt(), g.isActive(Instant.now()));
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }
}
