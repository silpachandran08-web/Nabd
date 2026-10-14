package com.nabd.hms.patient;

import com.nabd.hms.common.PhoneNumbers;
import com.nabd.hms.patient.dto.PatientRegistryResponse;
import com.nabd.hms.common.ClinicClock;
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
import com.nabd.hms.patient.dto.PatientPatchRequest;
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

    private final ClinicClock clock;

    PatientService(PatientRepository repo, TenantContext tenantContext, AesGcmCipher cipher,
                    StepUpVerifier stepUpVerifier, StaffService staffService, AuditService auditService, ClinicClock clock) {
        this.clock = clock;
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.cipher = cipher;
        this.stepUpVerifier = stepUpVerifier;
        this.staffService = staffService;
        this.auditService = auditService;
    }

    public static final List<String> REGISTRY_FILTERS = List.of("all", "recent", "followup", "package", "balance", "duplicate", "archived");
    private static final int RECENT_VISIT_DAYS = 90;
    // ponytail: flags are computed for up to REGISTRY_CAP patients per request, then filtered and
    // searched in memory; move filtering into SQL with keyset paging once a clinic outgrows this.
    private static final int REGISTRY_CAP = 3000;
    private static final int REGISTRY_ROWS = 200;

    /**
     * Patient Registry: per-patient visit/appointment/package/balance/clinical flags, one count per filter.
     * REC-040: rows are paged with limit (clamped to 1..REGISTRY_ROWS, default REGISTRY_ROWS, so a call without
     * paging params behaves exactly as before) and an opaque offset cursor over the name-then-id ordered result.
     * Offset, not keyset: the rows are filtered and searched in memory (see REGISTRY_CAP), and the order is
     * stable (name, id), so a page boundary only shifts if patients are added or removed between requests.
     */
    @Transactional(readOnly = true)
    public PatientRegistryResponse registry(UUID tenantId, UUID callerStaffId, String q, String filter,
                                            Integer limit, String cursor) {
        UUID scopedToDoctorId = requireVerifiedAndResolveScope(tenantId, callerStaffId);
        tenantContext.set(tenantId);
        String f = filter == null || filter.isBlank() ? "all" : filter;
        if (!REGISTRY_FILTERS.contains(f)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-filter", "Invalid filter",
                    "filter must be one of " + String.join(", ", REGISTRY_FILTERS) + ".");
        }
        java.time.ZoneId zone = clock.zone(tenantId);
        LocalDate today = LocalDate.now(zone);
        List<PatientRegistryResponse.Row> all = repo.registry(tenantId, scopedToDoctorId, today,
                today.atStartOfDay(zone).toInstant(), REGISTRY_CAP).stream().map(r -> {
            boolean archived = !"active".equals(r.status());
            List<String> tags = new java.util.ArrayList<>();
            if (archived) tags.add("archived");
            if (!archived && r.lastVisit() != null && !r.lastVisit().isBefore(today.minusDays(RECENT_VISIT_DAYS))) tags.add("recent");
            if (!archived && r.followUpDue()) tags.add("followup");
            if (!archived && r.packageTotal() > 0) tags.add("package");
            if (r.balanceDue()) tags.add("balance");
            if (r.duplicate()) tags.add("duplicate");
            boolean nextToday = r.nextStart() != null && r.nextStart().atZone(zone).toLocalDate().equals(today);
            return new PatientRegistryResponse.Row(r.id(), r.mrn(), r.name(), r.phone(),
                    java.time.Period.between(r.dob(), today).getYears(), r.gender(), isMinor(r.dob(), today),
                    r.lastVisit(), r.nextStart(), r.nextDoctorName(), r.nextDoctorId(), r.nextAppointmentId(), nextToday,
                    r.packageTotal() > 0 ? r.packageUsed() : null, r.packageTotal() > 0 ? r.packageTotal() : null,
                    r.condition(), r.followUpDue(), r.allergy(), r.balanceDue(), r.duplicate(), archived,
                    r.queueToken(), r.queueStatus(), List.copyOf(tags));
        }).toList();

        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String key : REGISTRY_FILTERS) {
            counts.put(key, (int) all.stream().filter(r -> inFilter(r, key)).count());
        }
        String needle = q == null ? "" : q.strip().toLowerCase();
        List<PatientRegistryResponse.Row> matched = all.stream()
                .filter(r -> inFilter(r, f))
                .filter(r -> needle.isEmpty() || matches(r, needle))
                .toList();
        int size = limit == null ? REGISTRY_ROWS : Math.max(1, Math.min(limit, REGISTRY_ROWS));
        int offset = cursor == null ? 0 : decodeRegistryCursor(cursor);
        int from = Math.min(offset, matched.size());
        int to = Math.min(from + size, matched.size());
        boolean hasMore = to < matched.size();
        String nextCursor = hasMore ? encodeRegistryCursor(to) : null;
        return new PatientRegistryResponse(counts.get("all"), counts, matched.subList(from, to),
                matched.size(), hasMore, new PageMeta(nextCursor, size));
    }

    private static final String REGISTRY_CURSOR_PREFIX = "reg:";

    private static String encodeRegistryCursor(int offset) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString((REGISTRY_CURSOR_PREFIX + offset).getBytes(StandardCharsets.UTF_8));
    }

    /** Same 400 invalid-cursor problem as common.Cursor for anything that isn't a cursor this endpoint issued. */
    private static int decodeRegistryCursor(String value) {
        try {
            String raw = new String(java.util.Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            if (raw.startsWith(REGISTRY_CURSOR_PREFIX)) {
                int offset = Integer.parseInt(raw.substring(REGISTRY_CURSOR_PREFIX.length()));
                if (offset >= 0) {
                    return offset;
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to the 400 below
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-cursor", "Invalid cursor",
                "The pagination cursor is malformed.");
    }

    private static boolean inFilter(PatientRegistryResponse.Row r, String filter) {
        return "all".equals(filter) ? !r.archived() : r.tags().contains(filter);
    }

    /** Name, MRN, mobile digits, or today's token ("T-12" / "12"). */
    private static boolean matches(PatientRegistryResponse.Row r, String needle) {
        String digits = needle.replaceAll("\\D", "");
        String token = needle.startsWith("t-") ? needle.substring(2) : needle;
        return r.name().toLowerCase().contains(needle)
                || r.mrn().toLowerCase().contains(needle)
                || (digits.length() >= 3 && r.phone().replaceAll("\\D", "").contains(digits))
                || (r.queueToken() != null && token.equals(String.valueOf(r.queueToken())));
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

        String phone = PhoneNumbers.e164(req.phone(), repo.region(tenantId)); // one stored format, whatever was typed
        List<MatchCandidateRow> candidates = Boolean.TRUE.equals(req.confirmedNotDuplicate())
                ? List.of() // reviewed and confirmed distinct; the registry's Duplicate review flag still surfaces it
                : repo.findDuplicateCandidates(tenantId, phone, req.name(), req.dob(), 5);
        if (!candidates.isEmpty()) {
            // count only — candidate ids/names/phones are PHI, don't belong in an operational log
            log.info("patient registration blocked by {}: {} duplicate candidate(s) (tenant {})",
                    callerStaffId, candidates.size(), tenantId);
            return new DuplicateCandidatesResponse(candidates.stream()
                    .map(c -> new PatientMatchCandidateResponse(c.id(), c.name(), c.phone(), c.matchScore(), c.matchedFields()))
                    .toList());
        }

        byte[] nationalIdEnc = encryptOrNull(req.nationalId());
        UUID id = repo.insert(tenantId, req.name(), phone, req.dob(), req.gender(),
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

    /**
     * True partial update: only the properties present in the request are written (an absent
     * address / nationalId / guardianId is left alone; an explicit JSON null clears it). Revoking a
     * guardian is therefore just {"guardianId": null} and cannot touch the other columns.
     */
    @Transactional
    public PatientResponse patch(UUID tenantId, UUID callerStaffId, UUID id, PatientPatchRequest req) {
        UUID scopedToDoctorId = requireVerifiedAndResolveScope(tenantId, callerStaffId);
        tenantContext.set(tenantId);
        // scoped out or genuinely absent look identical — both 404, never 403 (NB-051)
        PatientRow before = repo.findVisibleById(tenantId, id, scopedToDoctorId)
                .filter(r -> "active".equals(r.status())).orElseThrow(this::notFound);
        for (String required : List.of("name", "phone", "dob", "gender")) {
            if (req.has(required) && valueOf(req, required) == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                        required + " cannot be cleared.");
            }
        }
        // V1-015 / V3-010: a blank-but-present national ID or address must not act as a silent clear; clearing is an explicit null.
        if (req.has("nationalId") && req.nationalId() != null && req.nationalId().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                    "nationalId cannot be blank. Send null to clear it.");
        }
        if (req.has("address") && req.address() != null && req.address().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                    "address cannot be blank. Send null to clear it.");
        }
        boolean hadNationalId = req.has("nationalId") && repo.hasNationalId(tenantId, id);
        LocalDate dob = req.has("dob") ? req.dob() : before.dob();
        UUID guardianId = req.has("guardianId") ? req.guardianId() : before.guardianId();
        validateGuardian(tenantId, dob, guardianId);

        java.util.Map<String, Object> changes = new java.util.LinkedHashMap<>();
        if (req.has("name")) changes.put("name", req.name());
        if (req.has("phone")) changes.put("phone", PhoneNumbers.e164(req.phone(), repo.region(tenantId)));
        if (req.has("dob")) changes.put("dob", java.sql.Date.valueOf(req.dob()));
        if (req.has("gender")) changes.put("gender", req.gender());
        if (req.has("guardianId")) changes.put("guardian_id", req.guardianId());
        if (req.has("address")) changes.put("address", req.address());
        if (req.has("nationalId")) changes.put("national_id_enc", encryptOrNull(req.nationalId()));
        repo.update(tenantId, id, changes);
        auditUpdate(tenantId, callerStaffId, id, before, req, changes.get("phone"), hadNationalId);
        // NB-081/NB-085: this is the one place guardian_id ever changes post-registration, so it's
        // the one place that needs to keep guardian_access consent (and the audit trail) in step —
        // covers grant, revoke (new guardianId is null) and reassignment alike.
        if (!Objects.equals(before.guardianId(), guardianId)) {
            changeGuardian(tenantId, callerStaffId, id, before.guardianId(), guardianId);
        }
        log.info("patient {} updated by {} (tenant {})", id, callerStaffId, tenantId);
        return toResponse(repo.findById(tenantId, id).orElseThrow());
    }

    /** V1-015: every PATCH that changes something leaves a `patient.updated` audit row. Field NAMES only: no name,
     * phone, address or national-ID values (PHI) go into the payload. `cleared` names the fields that were emptied, so
     * a destructive clear of the national ID or address is visible. Guardian changes keep their own detailed events. */
    private void auditUpdate(UUID tenantId, UUID callerStaffId, UUID id, PatientRow before, PatientPatchRequest req,
                             Object normalisedPhone, boolean hadNationalId) {
        List<String> changed = new java.util.ArrayList<>();
        List<String> cleared = new java.util.ArrayList<>();
        if (req.has("name") && !Objects.equals(before.name(), req.name())) changed.add("name");
        if (req.has("phone") && !Objects.equals(before.phone(), normalisedPhone)) changed.add("phone");
        if (req.has("dob") && !Objects.equals(before.dob(), req.dob())) changed.add("dob");
        if (req.has("gender") && !Objects.equals(before.gender(), req.gender())) changed.add("gender");
        if (req.has("guardianId") && !Objects.equals(before.guardianId(), req.guardianId())) changed.add("guardianId");
        if (req.has("address") && !Objects.equals(before.address(), req.address())) {
            changed.add("address");
            if (req.address() == null) cleared.add("address");
        }
        // shortcut: a non-null nationalId is always listed as changed (the stored value is encrypted, so equality is not checked).
        if (req.has("nationalId") && (req.nationalId() != null || hadNationalId)) {
            changed.add("nationalId");
            if (req.nationalId() == null) cleared.add("nationalId");
        }
        if (changed.isEmpty()) {
            return;
        }
        audit(tenantId, callerStaffId, "patient.updated", id, null, Map.of("fields", changed, "cleared", cleared));
    }

    private static Object valueOf(PatientPatchRequest req, String property) {
        return switch (property) {
            case "name" -> req.name();
            case "phone" -> req.phone();
            case "dob" -> req.dob();
            default -> req.gender();
        };
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
    private static boolean isMinor(LocalDate dob, LocalDate today) {
        return Period.between(dob, today).getYears() < 18;
    }

    private void validateGuardian(UUID tenantId, PatientWriteRequest req) {
        validateGuardian(tenantId, req.dob(), req.guardianId());
    }

    private void validateGuardian(UUID tenantId, LocalDate dob, UUID guardianId) {
        if (isMinor(dob, clock.today(tenantId)) && guardianId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "guardian-required", "Guardian required",
                    "A minor cannot be registered without a guardian.");
        }
        if (guardianId != null && !repo.existsActive(tenantId, guardianId)) {
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
                row.status(), isMinor(row.dob(), clock.today(row.tenantId())));
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
                row.status(), allergies, chronicConditions, 0, outstandingBalance, lastVisitAt, isMinor(row.dob(), clock.today(row.tenantId())),
                row.guardianId(), guardianName, guardianConsentGrantedAt);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found",
                "The requested resource was not found.");
    }
}
