package com.nabd.hms.patient;

import com.nabd.hms.common.AesGcmCipher;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.Cursor;
import com.nabd.hms.common.StepUpVerifier;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.patient.dto.DuplicateCandidatesResponse;
import com.nabd.hms.patient.dto.GuardianReviewResponse;
import com.nabd.hms.patient.dto.MergeRequest;
import com.nabd.hms.patient.dto.PageMeta;
import com.nabd.hms.patient.dto.PatientDetailResponse;
import com.nabd.hms.patient.dto.PatientMatchCandidateResponse;
import com.nabd.hms.patient.dto.PatientPage;
import com.nabd.hms.patient.dto.PatientResponse;
import com.nabd.hms.patient.dto.PatientWriteRequest;
import com.nabd.hms.staff.StaffService;
import com.nabd.hms.staff.dto.CallerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.nabd.hms.patient.PatientModels.ActorInfo;
import static com.nabd.hms.patient.PatientModels.MatchCandidateRow;
import static com.nabd.hms.patient.PatientModels.PatientRow;

@Service
public class PatientService {

    private static final Logger log = LoggerFactory.getLogger(PatientService.class);
    private static final String GUARDIAN_ACCESS = "guardian_access";

    private final PatientRepository repo;
    private final TenantContext tenantContext;
    private final AesGcmCipher cipher;
    private final StepUpVerifier stepUpVerifier;
    private final StaffService staffService;
    private final AuditService auditService;

    PatientService(PatientRepository repo, TenantContext tenantContext, AesGcmCipher cipher,
                    StepUpVerifier stepUpVerifier, StaffService staffService, AuditService auditService) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.cipher = cipher;
        this.stepUpVerifier = stepUpVerifier;
        this.staffService = staffService;
        this.auditService = auditService;
    }

    @Transactional
    public PatientPage list(UUID tenantId, UUID callerStaffId, String q, int limit, String cursor) {
        UUID scopedToDoctorId = requireVerifiedAndResolveScope(tenantId, callerStaffId);
        tenantContext.set(tenantId);

        if (q != null && !q.isBlank()) {
            // search is relevance-ranked, not stably ordered, so it isn't cursor-paginated —
            // capped top-N is the right shape for a clinic-sized patient search anyway
            List<PatientRow> rows = repo.search(tenantId, q.trim(), scopedToDoctorId, limit);
            return new PatientPage(rows.stream().map(this::toResponse).toList(), new PageMeta(null, limit));
        }

        Cursor after = cursor == null ? null : Cursor.decode(cursor);
        List<PatientRow> rows = repo.listPage(tenantId, scopedToDoctorId, limit + 1,
                after == null ? null : after.createdAt(), after == null ? null : after.id());

        boolean hasMore = rows.size() > limit;
        List<PatientRow> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore
                ? new Cursor(page.get(page.size() - 1).createdAt(), page.get(page.size() - 1).id()).encode()
                : null;

        return new PatientPage(page.stream().map(this::toResponse).toList(), new PageMeta(nextCursor, limit));
    }

    /** Returns PatientResponse on success, DuplicateCandidatesResponse if a likely-duplicate match blocks it. */
    @Transactional
    public Object register(UUID tenantId, UUID callerStaffId, PatientWriteRequest req) {
        requireVerifiedAndResolveScope(tenantId, callerStaffId);
        tenantContext.set(tenantId);
        validateGuardian(tenantId, req);

        List<MatchCandidateRow> candidates = repo.findDuplicateCandidates(tenantId, req.phone(), req.name(), req.dob(), 5);
        if (!candidates.isEmpty()) {
            // count only — candidate ids/names/phones are PHI, don't belong in an operational log
            log.info("patient registration blocked by {}: {} duplicate candidate(s) (tenant {})",
                    callerStaffId, candidates.size(), tenantId);
            return new DuplicateCandidatesResponse(candidates.stream()
                    .map(c -> new PatientMatchCandidateResponse(c.id(), c.name(), c.phone(), c.matchScore()))
                    .toList());
        }

        byte[] nationalIdEnc = encryptOrNull(req.nationalId());
        UUID id = repo.insert(tenantId, req.name(), req.phone(), req.dob(), req.gender(),
                req.guardianId(), req.address(), nationalIdEnc);
        if (req.guardianId() != null) {
            grantGuardianConsent(tenantId, callerStaffId, id, req.guardianId());
        }
        log.info("patient {} registered by {} (tenant {})", id, callerStaffId, tenantId);
        return toResponse(repo.findById(tenantId, id).orElseThrow());
    }

    @Transactional
    public PatientDetailResponse get(UUID tenantId, UUID callerStaffId, UUID id) {
        UUID scopedToDoctorId = requireVerifiedAndResolveScope(tenantId, callerStaffId);
        tenantContext.set(tenantId);
        PatientRow row = repo.findVisibleById(tenantId, id, scopedToDoctorId).orElseThrow(this::notFound);
        List<String> fieldGrants = staffService.getCallerInfo(tenantId, callerStaffId).fieldGrants();
        return toDetailResponse(tenantId, row, repo.findLastVisitAt(tenantId, id).orElse(null), repo.findActiveAllergySubstances(tenantId, id),
                repo.findActiveConditionNames(tenantId, id), fieldGrants);
    }

    @Transactional
    public PatientResponse patch(UUID tenantId, UUID callerStaffId, UUID id, PatientWriteRequest req) {
        UUID scopedToDoctorId = requireVerifiedAndResolveScope(tenantId, callerStaffId);
        tenantContext.set(tenantId);
        // scoped out or genuinely absent look identical — both 404, never 403 (NB-051)
        PatientRow before = repo.findVisibleById(tenantId, id, scopedToDoctorId)
                .filter(r -> "active".equals(r.status())).orElseThrow(this::notFound);
        validateGuardian(tenantId, req);

        byte[] nationalIdEnc = encryptOrNull(req.nationalId());
        repo.update(tenantId, id, req.name(), req.phone(), req.dob(), req.gender(),
                req.guardianId(), req.address(), nationalIdEnc);
        // NB-081/NB-085: this is the one place guardian_id ever changes post-registration, so it's
        // the one place that needs to keep guardian_access consent (and the audit trail) in step —
        // covers grant, revoke (new guardianId is null) and reassignment alike.
        if (!Objects.equals(before.guardianId(), req.guardianId())) {
            changeGuardian(tenantId, callerStaffId, id, before.guardianId(), req.guardianId());
        }
        log.info("patient {} updated by {} (tenant {})", id, callerStaffId, tenantId);
        return toResponse(repo.findById(tenantId, id).orElseThrow());
    }

    /** NB-082: patients who've turned 18 but still have a guardian on file — the "automatic" part
     * of the age-18 handover is this always-current computed worklist; staff perform the actual
     * handover with a normal patch() clearing guardianId, which changeGuardian() already audits. */
    @Transactional
    public List<GuardianReviewResponse> guardianReviewsDue(UUID tenantId, UUID callerStaffId) {
        requireVerifiedAndResolveScope(tenantId, callerStaffId);
        tenantContext.set(tenantId);
        return repo.findGuardianReviewsDue(tenantId).stream()
                .map(r -> new GuardianReviewResponse(r.id(), r.mrn(), r.name(), r.dob(), r.guardianId(),
                        repo.findById(tenantId, r.guardianId()).map(PatientRow::name).orElse(null)))
                .toList();
    }

    private void grantGuardianConsent(UUID tenantId, UUID callerStaffId, UUID patientId, UUID guardianId) {
        repo.grantConsent(tenantId, patientId, GUARDIAN_ACCESS);
        audit(tenantId, callerStaffId, "patient.guardian_grant", patientId, null, Map.of("guardianId", guardianId));
    }

    private void changeGuardian(UUID tenantId, UUID callerStaffId, UUID patientId, UUID oldGuardianId, UUID newGuardianId) {
        if (oldGuardianId != null) {
            repo.withdrawConsent(tenantId, patientId, GUARDIAN_ACCESS);
        }
        if (newGuardianId != null) {
            repo.grantConsent(tenantId, patientId, GUARDIAN_ACCESS);
        }
        String action = newGuardianId == null ? "patient.guardian_revoke"
                : oldGuardianId == null ? "patient.guardian_grant" : "patient.guardian_reassign";
        audit(tenantId, callerStaffId, action, patientId,
                Map.of("guardianId", oldGuardianId == null ? "" : oldGuardianId.toString()),
                Map.of("guardianId", newGuardianId == null ? "" : newGuardianId.toString()));
    }

    private void audit(UUID tenantId, UUID callerStaffId, String action, UUID entityId, Object before, Object after) {
        ActorInfo actor = repo.findActorInfo(tenantId, callerStaffId).orElseThrow(this::notFound);
        auditService.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), null,
                action, "patient", entityId, before, after);
    }

    @Transactional
    public PatientDetailResponse merge(UUID tenantId, UUID survivorId, UUID callerStaffId,
                                        String stepUpToken, MergeRequest req) {
        stepUpVerifier.require(stepUpToken, callerStaffId);
        requireVerifiedAndResolveScope(tenantId, callerStaffId);
        tenantContext.set(tenantId);

        if (survivorId.equals(req.duplicatePatientId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "merge-self", "Cannot merge a patient into itself",
                    "duplicatePatientId must be different from the target patient.");
        }
        if (!repo.existsActive(tenantId, survivorId) || !repo.existsActive(tenantId, req.duplicatePatientId())) {
            throw notFound();
        }

        repo.markMerged(tenantId, req.duplicatePatientId(), survivorId, callerStaffId);
        log.info("patient {} merged into {} by {} (tenant {})", req.duplicatePatientId(), survivorId, callerStaffId, tenantId);
        PatientRow row = repo.findById(tenantId, survivorId).orElseThrow(this::notFound);
        List<String> fieldGrants = staffService.getCallerInfo(tenantId, callerStaffId).fieldGrants();
        return toDetailResponse(tenantId, row, repo.findLastVisitAt(tenantId, survivorId).orElse(null),
                repo.findActiveAllergySubstances(tenantId, survivorId), repo.findActiveConditionNames(tenantId, survivorId), fieldGrants);
    }

    /** Reverses a merge — only within the 30-day window (NB-072). */
    @Transactional
    public void unmerge(UUID tenantId, UUID mergeId, UUID callerStaffId, String stepUpToken) {
        stepUpVerifier.require(stepUpToken, callerStaffId);
        tenantContext.set(tenantId);
        if (!repo.unmerge(tenantId, mergeId, callerStaffId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "merge-not-reversible", "Merge not reversible",
                    "This merge doesn't exist, was already reversed, or is past its 30-day reversal window.");
        }
        log.info("merge {} reversed by {} (tenant {})", mergeId, callerStaffId, tenantId);
    }

    @Transactional
    public void withdrawConsent(UUID tenantId, UUID callerStaffId, UUID patientId, String consentType) {
        requireVerifiedAndResolveScope(tenantId, callerStaffId);
        tenantContext.set(tenantId);
        if (!repo.existsActive(tenantId, patientId)) {
            throw notFound();
        }
        repo.withdrawConsent(tenantId, patientId, consentType);
        log.info("consent '{}' withdrawn for patient {} by {} (tenant {})", consentType, patientId, callerStaffId, tenantId);
    }

    /** NB-041 gate + NB-051 scope resolution in one caller lookup — every patient-facing method needs both. */
    private UUID requireVerifiedAndResolveScope(UUID tenantId, UUID callerStaffId) {
        CallerInfo caller = staffService.getCallerInfo(tenantId, callerStaffId);
        if (!caller.emailVerified() || !caller.mobileVerified()) {
            log.warn("patient-data access blocked: staff {} not fully verified (email={}, mobile={}, tenant {})",
                    callerStaffId, caller.emailVerified(), caller.mobileVerified(), tenantId);
            throw new ApiException(HttpStatus.FORBIDDEN, "verification-required", "Verification required",
                    "Both email and mobile verification must be complete before patient data is reachable.");
        }
        return "own_patients_only".equals(caller.scope()) ? callerStaffId : null;
    }

    /** NB-082/NB-089: the one age check every minor/guardian/marketing-block rule in this module
     * builds on. */
    private boolean isMinor(LocalDate dob) {
        return Period.between(dob, LocalDate.now()).getYears() < 18;
    }

    private void validateGuardian(UUID tenantId, PatientWriteRequest req) {
        if (isMinor(req.dob()) && req.guardianId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "guardian-required", "Guardian required",
                    "A minor cannot be registered without a guardian.");
        }
        if (req.guardianId() != null && !repo.existsActive(tenantId, req.guardianId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "guardian-not-found", "Guardian not found",
                    "guardianId does not reference an existing patient in this tenant.");
        }
    }

    private byte[] encryptOrNull(String nationalId) {
        return nationalId == null || nationalId.isBlank()
                ? null
                : cipher.encrypt(nationalId.getBytes(StandardCharsets.UTF_8));
    }

    private PatientResponse toResponse(PatientRow row) {
        return new PatientResponse(row.id(), row.mrn(), row.name(), row.phone(), row.dob(), row.gender(),
                row.status(), isMinor(row.dob()));
    }

    /** NB-052: outstandingBalance (the one financial field this response carries) is omitted from
     * the JSON entirely — not zeroed, not merely hidden — for a staff member whose field grants are
     * a non-empty custom restriction that doesn't include "financial". An empty grants list means no
     * custom restriction (the default: full access). */
    private PatientDetailResponse toDetailResponse(UUID tenantId, PatientRow row, java.time.Instant lastVisitAt, List<String> allergies,
                                                     List<String> chronicConditions, List<String> callerFieldGrants) {
        boolean canSeeFinancial = callerFieldGrants.isEmpty() || callerFieldGrants.contains("financial");
        Double outstandingBalance = canSeeFinancial ? 0.0 : null;
        String guardianName = row.guardianId() == null ? null
                : repo.findById(tenantId, row.guardianId()).map(PatientRow::name).orElse(null);
        java.time.Instant guardianConsentGrantedAt = row.guardianId() == null ? null
                : repo.findActiveConsentGrantedAt(tenantId, row.id(), GUARDIAN_ACCESS).orElse(null);
        return new PatientDetailResponse(row.id(), row.mrn(), row.name(), row.phone(), row.dob(), row.gender(),
                row.status(), allergies, chronicConditions, 0, outstandingBalance, lastVisitAt, isMinor(row.dob()),
                row.guardianId(), guardianName, guardianConsentGrantedAt);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found",
                "The requested resource was not found.");
    }
}
