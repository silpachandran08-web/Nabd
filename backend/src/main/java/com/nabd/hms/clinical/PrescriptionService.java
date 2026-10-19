package com.nabd.hms.clinical;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.clinical.dto.DoseCalculationResponse;
import com.nabd.hms.clinical.dto.DrugSuggestionResponse;
import com.nabd.hms.clinical.dto.PrescriptionAllergySnapshotResponse;
import com.nabd.hms.clinical.dto.PrescriptionWarningResponse;
import com.nabd.hms.clinical.dto.FavouriteRxSetItemResponse;
import com.nabd.hms.clinical.dto.FavouriteRxSetRequest;
import com.nabd.hms.clinical.dto.FavouriteRxSetResponse;
import com.nabd.hms.clinical.dto.FavouriteRxSetUpdateRequest;
import com.nabd.hms.clinical.dto.PrescriptionItemRequest;
import com.nabd.hms.clinical.dto.PrescriptionItemResponse;
import com.nabd.hms.clinical.dto.PrescriptionResponse;
import com.nabd.hms.clinical.dto.RxDecisionView;
import com.nabd.hms.clinical.dto.RxReasonOption;
import com.nabd.hms.clinical.dto.RxWarningDecision;
import com.nabd.hms.clinical.dto.RxWarningResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.clinical.AllergyModels.AllergyRow;
import static com.nabd.hms.clinical.AllergyModels.AssessmentRow;
import static com.nabd.hms.clinical.NoteModels.QueueEntryOwner;
import static com.nabd.hms.clinical.PrescriptionModels.ActorInfo;
import static com.nabd.hms.clinical.PrescriptionModels.FavouriteSetItemRow;
import static com.nabd.hms.clinical.PrescriptionModels.FavouriteSetRow;
import static com.nabd.hms.clinical.PrescriptionModels.PrescriptionItemRow;
import static com.nabd.hms.clinical.PrescriptionModels.PrescriptionRow;

/**
 * NB-109/105: free-text drug pad, no coded formulary (NB-010 doesn't exist). The allergy check is a
 * case-insensitive match of the drug name against the patient's own recorded allergy substances (plus a
 * short illustrative drug-class list for penicillin / sulfa / NSAID), not a real drug-interaction database.
 *
 * DOC-031/032 decision flow. Every safety warning on a line (allergy, pregnancy/lactation, controlled
 * substance) is returned as a structured warnings[] entry, recomputed on every read. A warning that
 * requiresDecision (a severe allergy match, a pregnancy/lactation caution, a controlled substance) must
 * carry a named reason from a fixed list before the prescription can be SIGNED. Saving a draft with an
 * unresolved warning is allowed so the doctor can see and decide; signing re-checks everything server-side
 * against the current allergies, so an allergy recorded after the draft was saved still blocks. A decision
 * records who and when, is audited when made, is kept on later saves while the same warning applies, and is
 * never silently cleared. Moderate and mild allergy matches stay advisory (NB-107).
 */
@Service
public class PrescriptionService {

    /** Role-matrix permission (module `clinical`, action `approve`) that lets someone other than the visit's doctor
     * record a warning decision or sign a prescription. */
    public static final String PERMISSION_CLINICAL_APPROVE = "clinical:approve";

    private static final String DOSE_RULE_SOURCE = "Weight-based mg/kg calculation v1 (clinician-supplied rate; no coded per-drug formulary)";

    private final PrescriptionRepository repo;
    private final AllergyRepository allergyRepo;
    private final VitalsRepository vitalsRepo;
    private final AuditService auditService;
    private final TenantContext tenantContext;

    private final ClinicClock clock;
    private final ObjectMapper objectMapper;
    private final SafetyDictionaryService dictionaries;
    private final ClinicalAuthority clinicalAuthority;
    private final EventPublisher eventPublisher;

    /** CLN-005: published on every successful sign (pharmacy worklist, comms, clinical-intelligence consume it). */
    public static final String SIGNED_EVENT = "clinical.prescription.signed.v1";

    PrescriptionService(PrescriptionRepository repo, AllergyRepository allergyRepo, VitalsRepository vitalsRepo,
                         AuditService auditService, TenantContext tenantContext, ClinicClock clock, ObjectMapper objectMapper,
                         SafetyDictionaryService dictionaries, ClinicalAuthority clinicalAuthority, EventPublisher eventPublisher) {
        this.dictionaries = dictionaries;
        this.clinicalAuthority = clinicalAuthority;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.repo = repo;
        this.allergyRepo = allergyRepo;
        this.vitalsRepo = vitalsRepo;
        this.auditService = auditService;
        this.tenantContext = tenantContext;
    }

    /** NB-112: dose derives from the patient's latest recorded weight (NB-106); the "rule" is a named,
     * versioned formula rather than a per-drug database this app has no source for. */
    @Transactional
    public DoseCalculationResponse calculateDose(UUID tenantId, UUID patientId, BigDecimal mgPerKg) {
        tenantContext.set(tenantId);
        if (mgPerKg == null || mgPerKg.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Invalid dose rate",
                    "mgPerKg must be a positive number.");
        }
        LocalDate dob = vitalsRepo.findPatientDob(tenantId, patientId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Patient not found", "No such patient."));
        java.time.ZoneId zone = clock.zone(tenantId);
        LocalDate today = clock.today(zone);
        if (DoseWeightPolicy.isAdult(dob, today)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "adult-patient", "Adult patient",
                    "Weight-based mg/kg dosing is for patients under 18; use the drug reference for adult doses.");
        }
        VitalsRepository.WeightReading reading = vitalsRepo.findLatestWeight(tenantId, patientId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "no-weight-recorded", "No weight recorded",
                        "Record this patient's weight in vitals before calculating a weight-based dose."));
        BigDecimal weightKg = reading.weightKg();
        BigDecimal totalDoseMg = weightKg.multiply(mgPerKg).setScale(2, RoundingMode.HALF_UP);
        return new DoseCalculationResponse(weightKg, mgPerKg, totalDoseMg, DOSE_RULE_SOURCE, reading.recordedAt(),
                DoseWeightPolicy.isStale(reading.recordedAt(), dob, today, zone));
    }

    /** CLN-005: what the consult screen needs to show or hide Sign. reason is null when the caller may sign. */
    @Transactional
    public Map<String, Object> signingAuthority(UUID tenantId, UUID queueEntryId, UUID callerStaffId, boolean callerCanApprove) {
        tenantContext.set(tenantId);
        QueueEntryOwner owner = repo.findQueueEntryOwner(tenantId, queueEntryId).orElseThrow(this::notFound);
        String reason = clinicalAuthority.refusal(tenantId, owner.doctorId(), callerStaffId, callerCanApprove);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("canSign", reason == null);
        out.put("reason", reason);
        return out;
    }

    @Transactional
    public PrescriptionResponse get(UUID tenantId, UUID queueEntryId) {
        tenantContext.set(tenantId);
        return toResponse(tenantId, repo.findByQueueEntry(tenantId, queueEntryId).orElseThrow(this::notFound));
    }

    @Transactional
    public List<PrescriptionResponse> previousForPatient(UUID tenantId, UUID patientId) {
        tenantContext.set(tenantId);
        return repo.findSignedByPatient(tenantId, patientId).stream().map(p -> toResponse(tenantId, p)).toList();
    }

    @Transactional
    public PrescriptionResponse upsert(UUID tenantId, UUID queueEntryId, UUID callerStaffId, boolean callerCanApprove,
                                        String ipAddress, List<PrescriptionItemRequest> items) {
        tenantContext.set(tenantId);
        QueueEntryOwner owner = repo.findQueueEntryOwner(tenantId, queueEntryId).orElseThrow(this::notFound);
        boolean mayDecide = mayDecide(owner, callerStaffId, callerCanApprove);

        // V1-004: serialise against sign() and against allergy-register writes for this patient. Lock order is
        // always register first, then the prescription row. Everything below runs inside the lock, so the
        // "already signed" check and the allergy snapshot can no longer go stale before the items are replaced.
        allergyRepo.lockRegister(tenantId, owner.patientId());
        UUID prescriptionId = repo.ensureDraft(tenantId, queueEntryId, owner.patientId(), owner.doctorId());
        PrescriptionRow current = repo.lockByQueueEntry(tenantId, queueEntryId).orElseThrow(this::notFound);
        if ("signed".equals(current.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "prescription-signed", "Prescription already signed",
                    "This prescription is signed and can no longer be edited.");
        }

        List<AllergyRow> allergies = allergyRepo.findActiveByPatient(tenantId, owner.patientId());
        PrescriptionRepository.PatientProfile profile = repo.findPatientProfile(tenantId, owner.patientId()).orElse(null);
        SafetyDictionary dict = dictionaryFor(tenantId);
        LocalDate today = clock.today(tenantId);
        List<PrescriptionItemRow> existing = repo.findItems(tenantId, prescriptionId);
        ActorInfo actor = repo.findActorInfo(tenantId, callerStaffId).orElseThrow(this::notFound);

        List<PrescriptionItemRow> rows = new ArrayList<>();
        List<StoredDecision> kept = new ArrayList<>();
        for (PrescriptionItemRequest item : items) {
            List<StoredDecision> decisions = new ArrayList<>();
            for (Detected d : detectLine(item.drugName(), item.brandName(), allergies, profile, dict, today)) {
                if (!d.requiresDecision()) {
                    continue;
                }
                RxWarningDecision requested = item.overrides() == null ? null : item.overrides().stream()
                        .filter(o -> d.kind().equals(o.kind())).findFirst().orElse(null);
                StoredDecision prior = findStored(existing, item.drugName(), d.kind(), d.matched());
                if (requested != null) {
                    if (RxSafetyChecks.reasonLabel(d.kind(), requested.reasonCode()) == null) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-override-reason", "Invalid reason",
                                "'" + requested.reasonCode() + "' is not an accepted reason for a " + d.kind() + " warning.");
                    }
                    String note = requested.note() == null || requested.note().isBlank() ? null : requested.note().trim();
                    if (prior != null && prior.reasonCode().equals(requested.reasonCode()) && Objects.equals(prior.note(), note)) {
                        decisions.add(prior);
                    } else {
                        if (!mayDecide) {
                            throw notPrescriber("record a warning decision on");
                        }
                        // CLN-005: an override decision carries the same licence rule as signing.
                        clinicalAuthority.require(tenantId, owner.doctorId(), callerStaffId, callerCanApprove,
                                "record a warning decision on this prescription");
                        StoredDecision fresh = new StoredDecision(d.kind(), requested.reasonCode(), note, d.matched(), d.severity(),
                                callerStaffId.toString(), actor.name(), clock.now().toString());
                        decisions.add(fresh);
                        auditDecision(tenantId, callerStaffId, actor, ipAddress, prescriptionId, item, fresh, "made");
                    }
                } else if (prior != null) {
                    decisions.add(prior);
                }
            }
            kept.addAll(decisions);
            rows.add(new PrescriptionItemRow(null, prescriptionId, item.drugName(), item.dosage(), item.frequency(),
                    item.duration(), item.instructions(), allergyLabel(decisions), 0, toJson(decisions),
                    blankToNull(item.brandName()), blankToNull(item.sigPattern()), blankToNull(item.route()),
                    item.quantity(), blankToNull(item.quantityUnit())));
        }
        // A decision that no longer applies (drug changed or removed, warning gone) is withdrawn on the record, not dropped silently.
        for (PrescriptionItemRow old : existing) {
            for (StoredDecision d : parseDecisions(old.overridesJson())) {
                boolean stillHeld = kept.stream().anyMatch(k -> k.kind().equals(d.kind()) && k.at().equals(d.at()));
                if (!stillHeld) {
                    auditWithdrawn(tenantId, callerStaffId, actor, ipAddress, prescriptionId, old.drugName(), d);
                }
            }
        }

        repo.replaceItems(tenantId, prescriptionId, rows);
        return toResponse(tenantId, repo.findByQueueEntry(tenantId, queueEntryId).orElseThrow(this::notFound));
    }

    /** NB-114 / DOC-035: a doctor's saved combo of drugs. scope "mine" (default) is private, "shared" is visible to the
     * clinic, "standard" is a clinic standard with no owner and needs clinical:approve. Items keep brand/sig/route/quantity. */
    @Transactional
    public FavouriteRxSetResponse createSet(UUID tenantId, UUID doctorId, boolean callerCanApprove, String ipAddress,
                                            FavouriteRxSetRequest req) {
        tenantContext.set(tenantId);
        String scope = req.scope() == null ? "mine" : req.scope();
        if (req.items().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty-set", "Nothing to save", "Add at least one medicine to the set.");
        }
        if ("standard".equals(scope) && !callerCanApprove) {
            throw setForbidden("create a clinic standard");
        }
        UUID setId = repo.insertSet(tenantId, "standard".equals(scope) ? null : doctorId, req.name().trim(), "shared".equals(scope));
        repo.insertSetItems(tenantId, setId, req.items());
        // DOC-R2: creation is audited like update and delete (same before/after shape).
        ActorInfo actor = repo.findActorInfo(tenantId, doctorId).orElseThrow(this::notFound);
        auditService.record(tenantId, "staff", doctorId, actor.name(), actor.role(), ipAddress, "rx_set.created", "rx_set", setId,
                null, Map.of("name", req.name().trim(), "shared", "shared".equals(scope), "standard", "standard".equals(scope),
                        "itemCount", req.items().size()));
        return toSetResponse(tenantId, repo.findSet(tenantId, setId).orElseThrow(this::notFound), doctorId, callerCanApprove,
                repo.activePharmacyNames(tenantId));
    }

    @Transactional
    public List<FavouriteRxSetResponse> listSets(UUID tenantId, UUID doctorId, boolean callerCanApprove) {
        tenantContext.set(tenantId);
        java.util.Set<String> master = repo.activePharmacyNames(tenantId);
        return repo.listSets(tenantId, doctorId).stream().map(s -> toSetResponse(tenantId, s, doctorId, callerCanApprove, master)).toList();
    }

    /** DOC-035: owner-only edit, or clinical:approve (which is also what a clinic standard needs). */
    @Transactional
    public FavouriteRxSetResponse updateSet(UUID tenantId, UUID setId, UUID callerStaffId, boolean callerCanApprove, String ipAddress,
                                             FavouriteRxSetUpdateRequest req) {
        tenantContext.set(tenantId);
        FavouriteSetRow set = repo.findSet(tenantId, setId).orElseThrow(this::notFound);
        requireVisible(set, callerStaffId);
        requireCanManage(set, callerStaffId, callerCanApprove, "change");
        if (req.items() != null && req.items().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty-set", "Nothing to save", "A set needs at least one medicine; delete it instead.");
        }
        String name = req.name() == null || req.name().isBlank() ? set.name() : req.name().trim();
        UUID owner = set.doctorId();
        boolean shared = set.shared();
        if (req.scope() != null) {
            switch (req.scope()) {
                case "standard" -> {
                    if (!callerCanApprove) {
                        throw setForbidden("make a set a clinic standard");
                    }
                    owner = null;
                    shared = false;
                }
                case "shared", "mine" -> {
                    if ("mine".equals(req.scope()) && owner != null && !owner.equals(callerStaffId)) {
                        throw setForbidden("make another doctor's shared set private"); // would hide it from its own owner
                    }
                    owner = owner == null ? callerStaffId : owner;
                    shared = "shared".equals(req.scope());
                }
                default -> throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-scope", "Invalid scope", "Scope must be mine, shared or standard.");
            }
        }
        repo.updateSet(tenantId, setId, name, owner, shared);
        if (req.items() != null) {
            repo.deleteSetItems(tenantId, setId);
            repo.insertSetItems(tenantId, setId, req.items());
        }
        ActorInfo actor = repo.findActorInfo(tenantId, callerStaffId).orElseThrow(this::notFound);
        auditService.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), ipAddress, "rx_set.updated", "rx_set", setId,
                Map.of("name", set.name(), "shared", set.shared(), "standard", set.doctorId() == null),
                Map.of("name", name, "shared", shared, "standard", owner == null, "itemsReplaced", req.items() != null));
        return toSetResponse(tenantId, repo.findSet(tenantId, setId).orElseThrow(this::notFound), callerStaffId, callerCanApprove,
                repo.activePharmacyNames(tenantId));
    }

    @Transactional
    public void deleteSet(UUID tenantId, UUID setId, UUID callerStaffId, boolean callerCanApprove, String ipAddress) {
        tenantContext.set(tenantId);
        FavouriteSetRow set = repo.findSet(tenantId, setId).orElseThrow(this::notFound);
        requireVisible(set, callerStaffId);
        requireCanManage(set, callerStaffId, callerCanApprove, "delete");
        ActorInfo actor = repo.findActorInfo(tenantId, callerStaffId).orElseThrow(this::notFound);
        repo.deleteSet(tenantId, setId); // items go with it (ON DELETE CASCADE); prescriptions already built from it are unaffected
        auditService.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), ipAddress, "rx_set.deleted", "rx_set", setId,
                Map.of("name", set.name(), "shared", set.shared(), "standard", set.doctorId() == null), null);
    }

    @Transactional
    public PrescriptionResponse applySet(UUID tenantId, UUID queueEntryId, UUID setId, UUID callerStaffId,
                                          boolean callerCanApprove, String ipAddress) {
        tenantContext.set(tenantId);
        FavouriteSetRow set = repo.findSet(tenantId, setId).orElseThrow(this::notFound);
        requireVisible(set, callerStaffId);
        // Brand, sig, route and quantity travel with the line: upsert() runs the allergy/pregnancy/controlled checks on
        // drug AND brand exactly as for hand-typed lines, so a brand that carries an allergen is flagged on apply.
        List<PrescriptionItemRequest> items = repo.findSetItems(tenantId, setId).stream()
                .map(i -> new PrescriptionItemRequest(i.drugName(), i.dosage(), i.frequency(), i.duration(),
                        i.instructions(), null, i.brandName(), i.sigPattern(), i.route(), i.quantity(), i.quantityUnit()))
                .toList();
        PrescriptionResponse result = upsert(tenantId, queueEntryId, callerStaffId, callerCanApprove, ipAddress, items);
        repo.markSetUsed(tenantId, setId);
        return result;
    }

    /** DOC-035 / DOC-R2 "Repeat last prescription": replace the draft with the lines of this patient's most recent SIGNED
     * prescription from another visit, keeping brand, sig, route and quantity. Earlier warning decisions are NOT carried
     * over: upsert() re-runs every allergy, pregnancy and controlled-substance check against the current register, so a
     * line that needs a decision needs a fresh one on this prescription. 404 no-previous-prescription when there is none. */
    @Transactional
    public PrescriptionResponse repeatLast(UUID tenantId, UUID queueEntryId, UUID callerStaffId, boolean callerCanApprove,
                                            String ipAddress) {
        tenantContext.set(tenantId);
        QueueEntryOwner owner = repo.findQueueEntryOwner(tenantId, queueEntryId).orElseThrow(this::notFound);
        PrescriptionRow last = repo.findSignedByPatient(tenantId, owner.patientId()).stream()
                .filter(p -> !p.queueEntryId().equals(queueEntryId))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "no-previous-prescription", "No previous prescription",
                        "This patient has no signed prescription from an earlier visit to repeat."));
        List<PrescriptionItemRequest> items = repo.findItems(tenantId, last.id()).stream()
                .map(i -> new PrescriptionItemRequest(i.drugName(), i.dosage(), i.frequency(), i.duration(),
                        i.instructions(), null, i.brandName(), i.sigPattern(), i.route(), i.quantity(), i.quantityUnit()))
                .toList();
        PrescriptionResponse result = upsert(tenantId, queueEntryId, callerStaffId, callerCanApprove, ipAddress, items);
        ActorInfo actor = repo.findActorInfo(tenantId, callerStaffId).orElseThrow(this::notFound);
        auditService.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), ipAddress, "prescription.repeated_last",
                "prescription", result.id(), null, Map.of("queueEntryId", queueEntryId.toString(),
                        "sourcePrescriptionId", last.id().toString(), "itemCount", items.size()));
        return result;
    }

    /** Another doctor's private set does not exist for this caller (404, no existence probe), approvers included:
     * clinical:approve only reaches shared and standard sets. */
    private void requireVisible(FavouriteSetRow set, UUID callerStaffId) {
        if (set.doctorId() != null && !set.shared() && !set.doctorId().equals(callerStaffId)) {
            throw notFound();
        }
    }

    private void requireCanManage(FavouriteSetRow set, UUID callerStaffId, boolean callerCanApprove, String verb) {
        if (!canManage(set, callerStaffId, callerCanApprove)) {
            throw setForbidden(verb);
        }
    }

    private static boolean canManage(FavouriteSetRow set, UUID callerStaffId, boolean callerCanApprove) {
        return callerCanApprove || (set.doctorId() != null && set.doctorId().equals(callerStaffId));
    }

    private ApiException setForbidden(String what) {
        return new ApiException(HttpStatus.FORBIDDEN, "rx-set-owner-only", "Not your set",
                "Only the doctor who owns this set, or a clinician with prescription approval rights, can " + what + " it.");
    }

    private FavouriteRxSetResponse toSetResponse(UUID tenantId, FavouriteSetRow s, UUID callerStaffId, boolean callerCanApprove,
                                                  java.util.Set<String> pharmacyMaster) {
        boolean checkable = !pharmacyMaster.isEmpty();
        List<FavouriteRxSetItemResponse> items = repo.findSetItems(tenantId, s.id()).stream()
                .map(i -> new FavouriteRxSetItemResponse(i.drugName(), i.dosage(), i.frequency(), i.duration(),
                        i.instructions(), i.brandName(), i.sigPattern(), i.route(), i.quantity(), i.quantityUnit(),
                        checkable ? isStale(i, pharmacyMaster) : null))
                .toList();
        String scope = s.doctorId() == null ? "standard" : s.doctorId().equals(callerStaffId) ? "mine" : "shared";
        int stale = (int) items.stream().filter(i -> Boolean.TRUE.equals(i.stale())).count();
        return new FavouriteRxSetResponse(s.id(), s.doctorId(), s.name(), s.createdAt(), items, scope, s.shared(), s.ownerName(),
                s.updatedAt(), s.lastUsedAt(), checkable, stale, canManage(s, callerStaffId, callerCanApprove));
    }

    /** Stale = the tenant has an active pharmacy item master and neither the drug nor the brand text is in it. */
    static boolean isStale(FavouriteSetItemRow i, java.util.Set<String> master) {
        String drug = i.drugName() == null ? "" : i.drugName().trim().toLowerCase(java.util.Locale.ROOT);
        String brand = i.brandName() == null ? "" : i.brandName().trim().toLowerCase(java.util.Locale.ROOT);
        return !(master.contains(drug) || (!brand.isEmpty() && master.contains(brand)));
    }

    /** Server-side gate: signing re-checks every line against the current allergy register and patient profile and
     * refuses while any warning still needs a decision, whatever the client showed. */
    @Transactional
    public PrescriptionResponse sign(UUID tenantId, UUID queueEntryId, UUID callerStaffId, boolean callerCanApprove,
                                      String ipAddress) {
        tenantContext.set(tenantId);
        QueueEntryOwner owner = repo.findQueueEntryOwner(tenantId, queueEntryId).orElseThrow(this::notFound);
        // V1-007 + CLN-005: the visit's doctor or a clinical:approve holder, who is also a registered clinician with an
        // in-date licence and clinical:edit (ClinicalAuthority). Checked before anything else, including "already signed".
        ClinicalAuthority.Licence licence = clinicalAuthority.require(tenantId, owner.doctorId(), callerStaffId, callerCanApprove,
                "sign this prescription");
        // V1-004: same lock order as upsert (register, then the prescription row). Warnings are evaluated and the
        // status flipped while holding the row lock, so a concurrent save cannot slip an unresolved line in between.
        allergyRepo.lockRegister(tenantId, owner.patientId());
        PrescriptionRow row = repo.lockByQueueEntry(tenantId, queueEntryId).orElseThrow(this::notFound);
        if ("signed".equals(row.status())) {
            return toResponse(tenantId, row); // idempotent: already signed, nothing more to record
        }
        // CLN-004: one dictionary snapshot for the gate below and for the version stamped on the signed row.
        SafetyDictionary dict = dictionaryFor(tenantId);
        PrescriptionResponse current = toResponse(tenantId, row, dict);
        if (current.items().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty-prescription", "Nothing to sign",
                    "Add at least one medicine before signing.");
        }
        if (current.unresolvedWarnings() > 0) {
            String lines = current.items().stream()
                    .filter(i -> i.warnings().stream().anyMatch(w -> w.requiresDecision() && !w.resolved()))
                    .map(i -> i.drugName() + " (" + i.warnings().stream().filter(w -> w.requiresDecision() && !w.resolved())
                            .map(RxWarningResponse::kind).distinct().collect(java.util.stream.Collectors.joining(", ")) + ")")
                    .collect(java.util.stream.Collectors.joining("; "));
            throw new ApiException(HttpStatus.CONFLICT, "warning-unresolved", "Resolve the warning first",
                    "These medicines carry a safety warning that needs a recorded decision before signing: " + lines + ".");
        }
        // DOC-R3: capture the allergy statement the gate above just checked (still under the register lock) and store
        // it with the status flip, so the printout shows what the prescriber signed against, not the register at print time.
        StoredAllergySnapshot snapshot = captureAllergySnapshot(tenantId, owner.patientId());
        if (repo.sign(tenantId, row.id(), toJson(snapshot), dict.label(), callerStaffId, licence.registrationNo()) == 0) {
            // Cannot happen while we hold the row lock on a draft; never write a "signed" audit row for a no-op.
            return toResponse(tenantId, repo.findByQueueEntry(tenantId, queueEntryId).orElseThrow(this::notFound));
        }
        ActorInfo actor = repo.findActorInfo(tenantId, callerStaffId).orElseThrow(this::notFound);
        List<Map<String, Object>> finalItems = current.items().stream().map(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("drugName", i.drugName());
            m.put("dosage", i.dosage());
            m.put("frequency", i.frequency());
            m.put("duration", i.duration());
            m.put("brandName", i.brandName());
            m.put("route", i.route());
            m.put("sigPattern", i.sigPattern());
            m.put("quantity", i.quantity() == null ? null : i.quantity().toPlainString());
            m.put("quantityUnit", i.quantityUnit());
            m.put("decisions", i.warnings().stream().filter(w -> w.decision() != null)
                    .map(w -> Map.of("kind", w.kind(), "reasonCode", w.decision().reasonCode(), "by", String.valueOf(w.decision().by()))).toList());
            return m;
        }).toList();
        auditService.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), ipAddress,
                "prescription.signed", "prescription", row.id(), null, Map.of("queueEntryId", queueEntryId.toString(), "items", finalItems,
                        "signedByVisitDoctor", owner.doctorId().equals(callerStaffId), "licenceNo", licence.registrationNo(),
                        "allergySnapshotState", snapshot.state(), "allergySnapshotActiveCount", snapshot.allergies().size(),
                        "safetyDictionaryVersion", dict.label()));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("tenantId", tenantId.toString());
        event.put("prescriptionId", row.id().toString());
        event.put("queueEntryId", queueEntryId.toString());
        event.put("signedBy", callerStaffId.toString());
        event.put("dictionaryVersion", dict.label()); // CLN-004's safety dictionary the sign was checked against
        eventPublisher.publish(tenantId, SIGNED_EVENT, event);
        return toResponse(tenantId, repo.findByQueueEntry(tenantId, queueEntryId).orElseThrow(this::notFound));
    }

    /** V1-007: the visit's own doctor, or someone holding the explicit clinical:approve grant. Draft editing stays
     * open to clinical:edit (a nurse may prepare a line); recording a warning decision and signing do not. */
    private static boolean mayDecide(QueueEntryOwner owner, UUID callerStaffId, boolean callerCanApprove) {
        return mayActAsVisitDoctor(owner.doctorId(), callerStaffId, callerCanApprove);
    }

    /** The same rule for other legal-weight outputs of a visit (clinical documents): the visit's doctor or clinical:approve. */
    public static boolean mayActAsVisitDoctor(UUID visitDoctorId, UUID callerStaffId, boolean callerCanApprove) {
        return callerCanApprove || (visitDoctorId != null && visitDoctorId.equals(callerStaffId));
    }

    private ApiException notPrescriber(String what) {
        return new ApiException(HttpStatus.FORBIDDEN, "not-prescriber", "Prescriber only",
                "Only the doctor on this visit, or a clinician with prescription approval rights, can " + what
                        + " this prescription.");
    }

    private SafetyDictionary dictionaryFor(UUID tenantId) {
        return dictionaries.forTenantRegion(repo.findTenantRegion(tenantId).orElse("IN"));
    }

    private PrescriptionResponse toResponse(UUID tenantId, PrescriptionRow p) {
        return toResponse(tenantId, p, dictionaryFor(tenantId));
    }

    private PrescriptionResponse toResponse(UUID tenantId, PrescriptionRow p, SafetyDictionary dict) {
        List<AllergyRow> allergies = allergyRepo.findActiveByPatient(tenantId, p.patientId());
        PrescriptionRepository.PatientProfile profile = repo.findPatientProfile(tenantId, p.patientId()).orElse(null);
        LocalDate today = clock.today(tenantId);
        int[] unresolved = {0};
        int[] itemNo = {0};
        List<PrescriptionWarningResponse> flat = new ArrayList<>();
        List<PrescriptionItemResponse> items = repo.findItems(tenantId, p.id()).stream()
                .map(i -> {
                    List<StoredDecision> stored = parseDecisions(i.overridesJson());
                    List<RxWarningResponse> warnings = new ArrayList<>();
                    String allergyWarning = null;
                    String pregnancyWarning = null;
                    String controlledWarning = null;
                    for (Detected d : detectLine(i.drugName(), i.brandName(), allergies, profile, dict, today)) {
                        StoredDecision held = !d.requiresDecision() ? null : stored.stream()
                                .filter(s -> s.kind().equals(d.kind()) && sameMatch(s.matched(), d.matched())
                                        && RxSafetyChecks.reasonLabel(d.kind(), s.reasonCode()) != null)
                                .findFirst().orElse(null);
                        boolean resolved = !d.requiresDecision() || held != null;
                        if (!resolved) {
                            unresolved[0]++;
                        }
                        warnings.add(new RxWarningResponse(d.kind(), d.severity(), d.message(), d.matched(), d.requiresDecision(), resolved,
                                d.requiresDecision() ? RxSafetyChecks.reasonOptions(d.kind()) : List.<RxReasonOption>of(), held == null ? null : view(held)));
                        switch (d.kind()) {
                            case RxSafetyChecks.KIND_ALLERGY -> allergyWarning = d.message();
                            case RxSafetyChecks.KIND_PREGNANCY -> pregnancyWarning = d.message();
                            default -> controlledWarning = d.message();
                        }
                    }
                    for (RxWarningResponse w : warnings) {
                        flat.add(new PrescriptionWarningResponse(itemNo[0], w.kind(), w.severity(), w.message()));
                    }
                    itemNo[0]++;
                    return new PrescriptionItemResponse(i.id(), i.drugName(), i.dosage(), i.frequency(), i.duration(),
                            i.instructions(), i.allergyOverrideReason(), i.displayOrder(), allergyWarning,
                            pregnancyWarning, controlledWarning, warnings, i.brandName(), i.sigPattern(), i.route(),
                            i.quantity(), i.quantityUnit());
                })
                .toList();
        return new PrescriptionResponse(p.id(), p.queueEntryId(), p.patientId(), p.doctorId(), p.status(),
                p.createdAt(), p.signedAt(), items, unresolved[0], readAllergySnapshot(p.allergySnapshotJson()), flat,
                p.safetyDictionaryVersion(), p.signedBy(), p.signedByRegistrationNo());
    }

    // ── DOC-R3: allergy statement snapshotted at signing ─────────────────

    /** Stored form in prescriptions.allergy_snapshot. Timestamps and ids are strings, like StoredDecision. */
    record StoredAllergySnapshot(String state, List<StoredSnapshotAllergy> allergies, String documentedBy,
                                 String documentedByName, String documentedAt) {
    }

    record StoredSnapshotAllergy(String substance, String severity, String reaction) {
    }

    /** Same rule as AllergyService.status(): any active entry = "recorded"; otherwise a "no known allergies" statement
     * = "none_documented"; otherwise "not_assessed". Caller must hold the patient's allergy-register lock. */
    private StoredAllergySnapshot captureAllergySnapshot(UUID tenantId, UUID patientId) {
        List<AllergyRow> active = allergyRepo.findActiveByPatient(tenantId, patientId);
        if (!active.isEmpty()) {
            List<StoredSnapshotAllergy> list = active.stream()
                    .map(a -> new StoredSnapshotAllergy(a.substance(), a.severity(), a.reaction())).toList();
            return new StoredAllergySnapshot("recorded", list, null, null, null);
        }
        AssessmentRow a = allergyRepo.findAssessment(tenantId, patientId).orElse(null);
        if (a == null) {
            return new StoredAllergySnapshot("not_assessed", List.of(), null, null, null);
        }
        String name = allergyRepo.findStaffNames(tenantId, java.util.Set.of(a.documentedBy())).get(a.documentedBy());
        return new StoredAllergySnapshot("none_documented", List.of(), a.documentedBy().toString(), name,
                a.documentedAt().toString());
    }

    /** Null in, null out: a draft or a prescription signed before V79 has no snapshot and must stay distinguishable. */
    private PrescriptionAllergySnapshotResponse readAllergySnapshot(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        StoredAllergySnapshot s;
        try {
            s = objectMapper.readValue(json, StoredAllergySnapshot.class);
        } catch (JsonProcessingException e) {
            // Never print a guessed statement: an unreadable snapshot is surfaced as an error, not as "none".
            throw new IllegalStateException("stored prescription allergy snapshot is unreadable", e);
        }
        List<PrescriptionAllergySnapshotResponse.Allergy> allergies = s.allergies() == null ? List.of() : s.allergies().stream()
                .map(x -> new PrescriptionAllergySnapshotResponse.Allergy(x.substance(), x.severity(), x.reaction())).toList();
        return new PrescriptionAllergySnapshotResponse(s.state(), allergies,
                s.documentedBy() == null ? null : UUID.fromString(s.documentedBy()), s.documentedByName(),
                s.documentedAt() == null ? null : Instant.parse(s.documentedAt()));
    }

    // ── warning detection and stored decisions ───────────────────────────

    private record Detected(String kind, String severity, String message, String matched, boolean requiresDecision) {
    }

    /** Decision as stored in prescription_items.overrides (by/at are stamped by the server, never by the client). */
    record StoredDecision(String kind, String reasonCode, String note, String matched, String severity, String by,
                           String byName, String at) {
    }

    private static final TypeReference<List<StoredDecision>> DECISIONS = new TypeReference<>() {
    };

    private List<Detected> detect(String drugName, List<AllergyRow> allergies, PrescriptionRepository.PatientProfile profile,
                                  SafetyDictionary dict, LocalDate today) {
        List<Detected> found = new ArrayList<>();
        findMatch(dict, allergies, drugName).ifPresent(a -> found.add(new Detected(RxSafetyChecks.KIND_ALLERGY, a.severity(),
                a.substance() + " (" + a.severity() + ")", a.substance(), "severe".equals(a.severity()))));
        String preg = profile == null ? null : RxSafetyChecks.pregnancyWarning(dict, drugName, profile.gender(), profile.dob(), today);
        // DOC-021: a recorded pregnant/lactating status escalates (severity, wording and a fresh decision: `matched`
        // differs from the generic one, so an earlier generic override does not carry over). Never weakens: any other
        // status falls through to the unchanged rule above.
        String recorded = profile == null ? null : RxSafetyChecks.recordedStatusWarning(dict, drugName, profile.pregnancyStatus());
        if (recorded != null) {
            found.add(new Detected(RxSafetyChecks.KIND_PREGNANCY, "severe", recorded, profile.pregnancyStatus(), true));
        } else if (preg != null) {
            found.add(new Detected(RxSafetyChecks.KIND_PREGNANCY, "caution", preg, "pregnancy/lactation", true));
        }
        String controlled = RxSafetyChecks.controlledSubstanceWarning(dict, drugName);
        if (controlled != null) {
            found.add(new Detected(RxSafetyChecks.KIND_CONTROLLED, "caution", controlled, "controlled substance", true));
        }
        return found;
    }

    /** DOC-033: the safety checks run on the generic text AND the brand text. A brand can never hide a conflict the
     * generic would show, nor the other way round. A finding already raised by the generic is not duplicated. */
    private List<Detected> detectLine(String drugName, String brandName, List<AllergyRow> allergies,
                                      PrescriptionRepository.PatientProfile profile, SafetyDictionary dict, LocalDate today) {
        List<Detected> found = new ArrayList<>(detect(drugName, allergies, profile, dict, today));
        if (brandName != null && !brandName.isBlank() && !brandName.trim().equalsIgnoreCase(drugName.trim())) {
            for (Detected d : detect(brandName, allergies, profile, dict, today)) {
                boolean dup = found.stream().anyMatch(f -> f.kind().equals(d.kind()) && sameMatch(f.matched(), d.matched()));
                if (!dup) {
                    found.add(d);
                }
            }
        }
        return found;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** DOC-033: type-ahead over the tenant's own pharmacy item master and prescribing history. Not a formulary. */
    @Transactional
    public List<DrugSuggestionResponse> searchDrugs(UUID tenantId, String q, int limit) {
        tenantContext.set(tenantId);
        String term = q == null ? "" : q.trim().toLowerCase(java.util.Locale.ROOT);
        if (term.length() < 2) {
            return List.of();
        }
        if (term.length() > 60) {
            term = term.substring(0, 60);
        }
        String escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        int capped = Math.max(1, Math.min(limit, 20));
        return repo.searchDrugs(tenantId, "%" + escaped + "%", escaped + "%", capped).stream()
                .map(r -> new DrugSuggestionResponse(r.name().trim(), r.source(), r.code(), r.inStock(), r.prescriptionOnly()))
                .toList();
    }

    /** The most severe matching allergy wins (severe, then moderate, then mild). */
    private Optional<AllergyRow> findMatch(SafetyDictionary dict, List<AllergyRow> allergies, String drugName) {
        return allergies.stream().filter(a -> RxSafetyChecks.allergyMatches(dict, drugName, a.substance()))
                .min(Comparator.comparingInt(a -> switch (a.severity()) {
                    case "severe" -> 0;
                    case "moderate" -> 1;
                    default -> 2;
                }));
    }

    private static boolean sameMatch(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private StoredDecision findStored(List<PrescriptionItemRow> existing, String drugName, String kind, String matched) {
        return existing.stream().filter(r -> r.drugName().trim().equalsIgnoreCase(drugName.trim()))
                .flatMap(r -> parseDecisions(r.overridesJson()).stream())
                .filter(d -> d.kind().equals(kind) && sameMatch(d.matched(), matched)
                        && RxSafetyChecks.reasonLabel(kind, d.reasonCode()) != null)
                .findFirst().orElse(null);
    }

    private RxDecisionView view(StoredDecision d) {
        UUID by = null;
        try {
            by = d.by() == null ? null : UUID.fromString(d.by());
        } catch (IllegalArgumentException ignored) {
            // keep null: the name snapshot is still shown
        }
        return new RxDecisionView(d.kind(), d.reasonCode(), RxSafetyChecks.reasonLabel(d.kind(), d.reasonCode()), d.note(), by,
                d.byName(), d.at() == null ? null : Instant.parse(d.at()));
    }

    private String allergyLabel(List<StoredDecision> decisions) {
        return decisions.stream().filter(d -> RxSafetyChecks.KIND_ALLERGY.equals(d.kind()))
                .map(d -> RxSafetyChecks.reasonLabel(d.kind(), d.reasonCode())).findFirst().orElse(null);
    }

    private List<StoredDecision> parseDecisions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, DECISIONS);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("prescription decision is not serializable", e);
        }
    }

    private void auditDecision(UUID tenantId, UUID staffId, ActorInfo actor, String ip, UUID prescriptionId,
                               PrescriptionItemRequest item, StoredDecision d, String phase) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("drugName", item.drugName());
        after.put("dosage", item.dosage());
        after.put("frequency", item.frequency());
        after.put("duration", item.duration());
        after.put("warningKind", d.kind());
        after.put("matched", d.matched());
        after.put("severity", d.severity());
        after.put("reasonCode", d.reasonCode());
        after.put("reasonLabel", RxSafetyChecks.reasonLabel(d.kind(), d.reasonCode()));
        after.put("note", d.note());
        after.put("decidedAt", d.at());
        String action = switch (d.kind()) {
            case RxSafetyChecks.KIND_ALLERGY -> "prescription.allergy_override";
            case RxSafetyChecks.KIND_PREGNANCY -> "prescription.pregnancy_override";
            default -> "prescription.controlled_acknowledged";
        };
        auditService.record(tenantId, "staff", staffId, actor.name(), actor.role(), ip, action, "prescription", prescriptionId, null, after);
    }

    private void auditWithdrawn(UUID tenantId, UUID staffId, ActorInfo actor, String ip, UUID prescriptionId, String drugName,
                                StoredDecision d) {
        auditService.record(tenantId, "staff", staffId, actor.name(), actor.role(), ip, "prescription.warning_decision_withdrawn",
                "prescription", prescriptionId, Map.of("drugName", drugName, "warningKind", d.kind(), "reasonCode", d.reasonCode(),
                        "decidedBy", String.valueOf(d.by()), "decidedAt", String.valueOf(d.at())), null);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }
}
