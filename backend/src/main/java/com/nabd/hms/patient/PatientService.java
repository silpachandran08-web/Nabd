package com.nabd.hms.patient;

import com.nabd.hms.common.PhoneNumbers;
import com.nabd.hms.patient.dto.PatientRegistryResponse;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.FieldCipher;
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
    private final FieldCipher cipher;
    private final StepUpVerifier stepUpVerifier;
    private final StaffService staffService;
    private final AuditService auditService;

    private final ClinicClock clock;
    private final com.nabd.hms.common.EventPublisher events;

    PatientService(PatientRepository repo, TenantContext tenantContext, FieldCipher cipher,
                    StepUpVerifier stepUpVerifier, StaffService staffService, AuditService auditService, ClinicClock clock,
                    com.nabd.hms.common.EventPublisher events) {
        this.events = events;
        this.clock = clock;
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.cipher = cipher;
        this.stepUpVerifier = stepUpVerifier;
        this.staffService = staffService;
        this.auditService = auditService;
    }

    public static final List<String> REGISTRY_FILTERS = List.of("all", "recent", "followup", "package", "balance", "duplicate", "archived");
    private static final int REGISTRY_ROWS = 200;

    /**
     * Patient Registry: per-patient visit/appointment/package/balance/clinical flags, one count per filter.
     * OPS-008: reads the patient_registry_summary read model (OPS-007) with the filter, search and keyset paging in
     * SQL, so every filter covers every patient (no cap). limit 1..REGISTRY_ROWS, default REGISTRY_ROWS. The read
     * model trails writes by the projector's poll (about a second); cross-patient duplicate flags by the nightly job.
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
        int size = limit == null ? REGISTRY_ROWS : limit;
        String[] after = cursor == null ? null : decodeRegistryCursor(tenantId, cursor);
        java.time.ZoneId zone = clock.zone(tenantId);
        LocalDate today = clock.today(zone);
        PatientRepository.RegistrySearch search = PatientRepository.RegistrySearch.of(q);
        PatientRepository.RegistryCounts counts = repo.registryCounts(tenantId, scopedToDoctorId, REGISTRY_FILTERS, f, search, today);
        List<PatientRepository.RegistryRow> found = repo.registryPage(tenantId, scopedToDoctorId, f, search, today,
                today.atStartOfDay(zone).toInstant(), after == null ? null : after[0], after == null ? null : UUID.fromString(after[1]), size + 1);
        boolean hasMore = found.size() > size;
        List<PatientRepository.RegistryRow> page = hasMore ? found.subList(0, size) : found;
        List<PatientRegistryResponse.Row> rows = page.stream().map(r -> {
            boolean archived = !"active".equals(r.status());
            List<String> tags = new java.util.ArrayList<>();
            if (archived) tags.add("archived");
            if (!archived && r.lastVisit() != null && !r.lastVisit().isBefore(today.minusDays(90))) tags.add("recent");
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
        PatientRepository.RegistryRow last = hasMore ? page.get(page.size() - 1) : null;
        String nextCursor = last == null ? null : encodeRegistryCursor(tenantId, last.nameSearch(), last.id());
        return new PatientRegistryResponse(counts.counts().get("all"), counts.counts(), rows,
                counts.matched(), hasMore, new PageMeta(nextCursor, size));
    }

    private static final String REGISTRY_CURSOR_PREFIX = "rk1|";

    /** PLT-044 AAD: a cursor opens only for the clinic that was issued it (not stored, so no row id). */
    private static FieldCipher.Aad registryCursorAad(UUID tenantId) {
        return FieldCipher.Aad.of(tenantId, "patient_registry", "cursor", "-");
    }

    /** Keyset (name_search, patient_id), encrypted: the sort key is a patient's name, which must never sit in a URL. */
    private String encodeRegistryCursor(UUID tenantId, String nameSearch, UUID id) {
        byte[] raw = (REGISTRY_CURSOR_PREFIX + id + "|" + nameSearch).getBytes(StandardCharsets.UTF_8);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(cipher.encrypt(raw, registryCursorAad(tenantId)));
    }

    /** {nameSearch, patientId}; the same 400 invalid-cursor problem as common.Cursor for anything this endpoint did not issue. */
    private String[] decodeRegistryCursor(UUID tenantId, String value) {
        try {
            String raw = new String(cipher.decrypt(java.util.Base64.getUrlDecoder().decode(value), registryCursorAad(tenantId)), StandardCharsets.UTF_8);
            if (raw.startsWith(REGISTRY_CURSOR_PREFIX)) {
                String[] parts = raw.substring(REGISTRY_CURSOR_PREFIX.length()).split("\\|", 2);
                UUID.fromString(parts[0]);
                return new String[]{parts[1], parts[0]};
            }
        } catch (RuntimeException ignored) {
            // fall through to the 400 below
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-cursor", "Invalid cursor",
                "The pagination cursor is malformed.");
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

    /** ACC-028: active patients whose stored phone failed validation (phone_invalid), keyset-paged by id. Only issue=phone exists. */
    @Transactional
    public com.nabd.hms.patient.dto.DataQualityPage dataQuality(UUID tenantId, UUID callerStaffId, String issue, int limit, String cursor) {
        if (!"phone".equals(issue)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "issue: must be phone",
                    Map.of("errors", List.of(Map.of("field", "issue", "message", "must be phone"))));
        }
        UUID after;
        try {
            after = cursor == null ? null : UUID.fromString(cursor);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-cursor", "Invalid cursor", "The pagination cursor is malformed.");
        }
        UUID scopedToDoctorId = requireVerifiedAndResolveScope(tenantId, callerStaffId);
        tenantContext.set(tenantId);
        List<com.nabd.hms.patient.dto.DataQualityPage.Item> rows = repo.findInvalidPhones(tenantId, scopedToDoctorId, after, limit + 1);
        boolean more = rows.size() > limit;
        List<com.nabd.hms.patient.dto.DataQualityPage.Item> page = more ? rows.subList(0, limit) : rows;
        return new com.nabd.hms.patient.dto.DataQualityPage(page, more ? page.get(limit - 1).patientId().toString() : null);
    }

    /** Returns PatientResponse on success, DuplicateCandidatesResponse if a likely-duplicate match blocks it. */
    @Transactional
    public Object register(UUID tenantId, UUID callerStaffId, PatientWriteRequest req) {
        requireVerifiedAndResolveScope(tenantId, callerStaffId);
        tenantContext.set(tenantId);
        validateGuardian(tenantId, req);
        if (req.guardianId() != null) {
            requireGuardianConsent(req.guardianConsent());
        }

        String phone = PhoneNumbers.require(req.phone(), repo.region(tenantId), "phone"); // ACC-028: valid for the clinic country, stored E.164
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

        UUID id = UUID.randomUUID();
        byte[] nationalIdEnc = encryptOrNull(tenantId, id, req.nationalId());
        repo.insert(id, tenantId, req.name(), phone, req.dob(), req.gender(),
                req.guardianId(), req.address(), nationalIdEnc);
        if (req.guardianId() != null) {
            grantGuardianConsent(tenantId, callerStaffId, id, req.guardianId(), req.guardianConsent().channel());
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
     * NUR-019: GET /patients/{id}'s response for many patients at once, keyed by id, with the same consent / own-patients
     * scope gates and field grants. Unlike get() it never throws: an unverified caller gets an empty map and a scoped-out
     * or missing patient is simply absent, so a list built on it degrades to "patient unknown" per row.
     * The base row, last visit, allergies and conditions are batched; guardian name/consent/review stay per-row
     * (toDetailResponse), which only costs queries for patients that have a guardian on file.
     */
    @Transactional
    public java.util.Map<UUID, PatientDetailResponse> getVisibleMany(UUID tenantId, UUID callerStaffId, java.util.Collection<UUID> ids) {
        CallerInfo caller = staffService.getCallerInfo(tenantId, callerStaffId);
        if (ids.isEmpty() || !caller.emailVerified() || !caller.mobileVerified()) {
            return java.util.Map.of();
        }
        tenantContext.set(tenantId);
        List<UUID> idList = List.copyOf(new java.util.LinkedHashSet<>(ids));
        UUID scopedToDoctorId = "own_patients_only".equals(caller.scope()) ? callerStaffId : null;
        java.util.Map<UUID, java.time.Instant> lastVisits = repo.findLastVisitAts(tenantId, idList);
        java.util.Map<UUID, List<String>> allergies = repo.findActiveAllergySubstances(tenantId, idList);
        java.util.Map<UUID, List<String>> conditions = repo.findActiveConditionNames(tenantId, idList);
        java.util.Map<UUID, PatientDetailResponse> out = new java.util.HashMap<>();
        for (PatientRow row : repo.findVisibleByIds(tenantId, idList, scopedToDoctorId)) {
            out.put(row.id(), toDetailResponse(tenantId, row, lastVisits.get(row.id()), allergies.getOrDefault(row.id(), List.of()),
                    conditions.getOrDefault(row.id(), List.of()), caller.fieldGrants()));
        }
        return out;
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
        if (req.has("phone")) {
            changes.put("phone", PhoneNumbers.require(req.phone(), repo.region(tenantId), "phone"));
            changes.put("phone_invalid", false); // ACC-028: a corrected number leaves the data-quality list
        }
        if (req.has("dob")) changes.put("dob", java.sql.Date.valueOf(req.dob()));
        if (req.has("gender")) changes.put("gender", req.gender());
        if (req.has("guardianId")) changes.put("guardian_id", req.guardianId());
        if (!Objects.equals(before.guardianId(), guardianId)) {
            changes.put("guardian_review_kept_at", null); // CGV-002: a different guardian needs a fresh review
            changes.put("guardian_review_kept_by", null);
        }
        if (req.has("address")) changes.put("address", req.address());
        if (req.has("nationalId")) changes.put("national_id_enc", encryptOrNull(tenantId, id, req.nationalId()));
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

    /** CGV-001: dedicated guardian revoke. Touches ONLY guardian_id and the guardian_access consent, so the
     * address / national ID can never be wiped by it. Idempotent: no guardian on file is a no-op 204. A minor
     * still needs a guardian (same rule as PATCH), so revoking one is rejected with guardian-required. */
    @Transactional
    public void revokeGuardian(UUID tenantId, UUID callerStaffId, UUID id) {
        UUID scopedToDoctorId = requireVerifiedAndResolveScope(tenantId, callerStaffId);
        tenantContext.set(tenantId);
        PatientRow before = repo.findVisibleById(tenantId, id, scopedToDoctorId)
                .filter(r -> "active".equals(r.status())).orElseThrow(this::notFound);
        if (before.guardianId() == null) {
            return;
        }
        validateGuardian(tenantId, before.dob(), null);
        repo.update(tenantId, id, java.util.Collections.singletonMap("guardian_id", null));
        changeGuardian(tenantId, callerStaffId, id, before.guardianId(), null);
        log.info("guardian revoked for patient {} by {} (tenant {})", id, callerStaffId, tenantId);
    }

    /** CGV-002: staff decide to keep the guardian as proxy after the patient turned 18. Stamps the review done (the
     * patient leaves the due worklist) and audits it; guardian_id and the guardian_access consent are untouched.
     * Revoke is the separate DELETE /guardian. Only valid for an adult who still has a guardian on file. */
    @Transactional
    public PatientDetailResponse keepGuardian(UUID tenantId, UUID callerStaffId, UUID id) {
        UUID scopedToDoctorId = requireVerifiedAndResolveScope(tenantId, callerStaffId);
        tenantContext.set(tenantId);
        PatientRow before = repo.findVisibleById(tenantId, id, scopedToDoctorId)
                .filter(r -> "active".equals(r.status())).orElseThrow(this::notFound);
        if (before.guardianId() == null || isMinor(before.dob(), clock.today(tenantId))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "guardian-review-not-due", "No guardian review due",
                    "Only an adult patient who still has a guardian on file has a guardian review.");
        }
        java.util.Map<String, Object> changes = new java.util.LinkedHashMap<>();
        changes.put("guardian_review_kept_at", java.sql.Timestamp.from(clock.now()));
        changes.put("guardian_review_kept_by", callerStaffId);
        repo.update(tenantId, id, changes);
        audit(tenantId, callerStaffId, "patient.guardian_review_kept", id, null, Map.of("guardianId", before.guardianId().toString()));
        return get(tenantId, callerStaffId, id);
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

    private static final List<String> CONSENT_CHANNELS = List.of("in_person", "phone", "written");

    /** CGV-005: no guardian link without an explicit confirmation and a known channel. */
    private static void requireGuardianConsent(PatientWriteRequest.GuardianConsent c) {
        if (c == null || !Boolean.TRUE.equals(c.confirmed()) || c.channel() == null || !CONSENT_CHANNELS.contains(c.channel())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "guardian-consent-required", "Guardian consent required",
                    "guardianConsent.confirmed must be true and guardianConsent.channel one of in_person, phone, written.");
        }
    }

    /** OPS-015: consent type code and status only. */
    private void consentChanged(UUID tenantId, UUID patientId, String consentType, String status) {
        events.publish(tenantId, com.nabd.hms.common.DomainEventType.CONSENT_CHANGED, patientId,
                Map.of("patientId", patientId, "consentType", consentType, "status", status));
    }

    private void grantGuardianConsent(UUID tenantId, UUID callerStaffId, UUID patientId, UUID guardianId, String channel) {
        repo.grantConsent(tenantId, patientId, GUARDIAN_ACCESS, callerStaffId, channel);
        consentChanged(tenantId, patientId, GUARDIAN_ACCESS, "granted");
        audit(tenantId, callerStaffId, "patient.guardian_grant", patientId, null,
                Map.of("guardianId", guardianId, "consentChannel", channel));
    }

    private void changeGuardian(UUID tenantId, UUID callerStaffId, UUID patientId, UUID oldGuardianId, UUID newGuardianId) {
        if (oldGuardianId != null) {
            repo.withdrawConsent(tenantId, patientId, GUARDIAN_ACCESS);
            consentChanged(tenantId, patientId, GUARDIAN_ACCESS, "withdrawn");
        }
        if (newGuardianId != null) {
            repo.grantConsent(tenantId, patientId, GUARDIAN_ACCESS);
            consentChanged(tenantId, patientId, GUARDIAN_ACCESS, "granted");
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
        consentChanged(tenantId, patientId, consentType, "withdrawn");
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

    private byte[] encryptOrNull(UUID tenantId, UUID patientId, String nationalId) {
        return nationalId == null || nationalId.isBlank()
                ? null
                : cipher.encrypt(nationalId.getBytes(StandardCharsets.UTF_8),
                        FieldCipher.Aad.of(tenantId, "patients", "national_id_enc", patientId));
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
                row.guardianId(), guardianName, guardianConsentGrantedAt,
                row.guardianId() == null ? null : repo.findGuardianReviewKeptAt(tenantId, row.id()).orElse(null),
                repo.findLastVisit(tenantId, row.id()).orElse(null));
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found",
                "The requested resource was not found.");
    }
}
