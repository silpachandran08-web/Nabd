package com.nabd.hms.patient;

import com.nabd.hms.common.AesGcmCipher;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.Cursor;
import com.nabd.hms.common.StepUpVerifier;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.patient.dto.DuplicateCandidatesResponse;
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
import java.util.UUID;

import static com.nabd.hms.patient.PatientModels.MatchCandidateRow;
import static com.nabd.hms.patient.PatientModels.PatientRow;

@Service
public class PatientService {

    private static final Logger log = LoggerFactory.getLogger(PatientService.class);

    private final PatientRepository repo;
    private final TenantContext tenantContext;
    private final AesGcmCipher cipher;
    private final StepUpVerifier stepUpVerifier;
    private final StaffService staffService;

    PatientService(PatientRepository repo, TenantContext tenantContext, AesGcmCipher cipher,
                    StepUpVerifier stepUpVerifier, StaffService staffService) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.cipher = cipher;
        this.stepUpVerifier = stepUpVerifier;
        this.staffService = staffService;
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
        log.info("patient {} registered by {} (tenant {})", id, callerStaffId, tenantId);
        return toResponse(repo.findById(tenantId, id).orElseThrow());
    }

    @Transactional
    public PatientDetailResponse get(UUID tenantId, UUID callerStaffId, UUID id) {
        UUID scopedToDoctorId = requireVerifiedAndResolveScope(tenantId, callerStaffId);
        tenantContext.set(tenantId);
        return repo.findVisibleById(tenantId, id, scopedToDoctorId).map(this::toDetailResponse).orElseThrow(this::notFound);
    }

    @Transactional
    public PatientResponse patch(UUID tenantId, UUID callerStaffId, UUID id, PatientWriteRequest req) {
        UUID scopedToDoctorId = requireVerifiedAndResolveScope(tenantId, callerStaffId);
        tenantContext.set(tenantId);
        // scoped out or genuinely absent look identical — both 404, never 403 (NB-051)
        repo.findVisibleById(tenantId, id, scopedToDoctorId).filter(r -> "active".equals(r.status())).orElseThrow(this::notFound);
        validateGuardian(tenantId, req);

        byte[] nationalIdEnc = encryptOrNull(req.nationalId());
        repo.update(tenantId, id, req.name(), req.phone(), req.dob(), req.gender(),
                req.guardianId(), req.address(), nationalIdEnc);
        log.info("patient {} updated by {} (tenant {})", id, callerStaffId, tenantId);
        return toResponse(repo.findById(tenantId, id).orElseThrow());
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
        return repo.findById(tenantId, survivorId).map(this::toDetailResponse).orElseThrow(this::notFound);
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

    private void validateGuardian(UUID tenantId, PatientWriteRequest req) {
        boolean isMinor = Period.between(req.dob(), LocalDate.now()).getYears() < 18;
        if (isMinor && req.guardianId() == null) {
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
        return new PatientResponse(row.id(), row.mrn(), row.name(), row.phone(), row.dob(), row.gender(), row.status());
    }

    private PatientDetailResponse toDetailResponse(PatientRow row) {
        return new PatientDetailResponse(row.id(), row.mrn(), row.name(), row.phone(), row.dob(), row.gender(),
                row.status(), List.of(), List.of(), 0, 0.0, null);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found",
                "The requested resource was not found.");
    }
}
