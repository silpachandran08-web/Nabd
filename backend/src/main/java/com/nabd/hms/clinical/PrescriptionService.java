package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.DoseCalculationResponse;
import com.nabd.hms.clinical.dto.FavouriteRxSetItemResponse;
import com.nabd.hms.clinical.dto.FavouriteRxSetRequest;
import com.nabd.hms.clinical.dto.FavouriteRxSetResponse;
import com.nabd.hms.clinical.dto.PrescriptionItemRequest;
import com.nabd.hms.clinical.dto.PrescriptionItemResponse;
import com.nabd.hms.clinical.dto.PrescriptionResponse;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.clinical.AllergyModels.AllergyRow;
import static com.nabd.hms.clinical.NoteModels.QueueEntryOwner;
import static com.nabd.hms.clinical.PrescriptionModels.ActorInfo;
import static com.nabd.hms.clinical.PrescriptionModels.FavouriteSetItemRow;
import static com.nabd.hms.clinical.PrescriptionModels.FavouriteSetRow;
import static com.nabd.hms.clinical.PrescriptionModels.PrescriptionItemRow;
import static com.nabd.hms.clinical.PrescriptionModels.PrescriptionRow;

/**
 * NB-109/105: free-text drug pad, no coded formulary (NB-010 doesn't exist). NB-108's hard warning
 * is a case-insensitive substring match against the patient's own recorded allergy substances — not
 * a real drug-interaction database, which this app has no source for.
 *
 * NB-107: severity grades the warning strength. A "severe" match is a hard block — save is rejected
 * without an allergyOverrideReason, and using the override is audited (NB-108). A "moderate"/"mild"
 * match never blocks; it's surfaced as PrescriptionItemResponse.allergyWarning instead, recomputed
 * fresh on every read so it stays current even against an allergy recorded after the prescription.
 */
@Service
public class PrescriptionService {

    private static final String DOSE_RULE_SOURCE = "Weight-based mg/kg calculation v1 (clinician-supplied rate; no coded per-drug formulary)";

    private final PrescriptionRepository repo;
    private final AllergyRepository allergyRepo;
    private final VitalsRepository vitalsRepo;
    private final AuditService auditService;
    private final TenantContext tenantContext;

    PrescriptionService(PrescriptionRepository repo, AllergyRepository allergyRepo, VitalsRepository vitalsRepo,
                         AuditService auditService, TenantContext tenantContext) {
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
        BigDecimal weightKg = vitalsRepo.findLatestWeightKg(tenantId, patientId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "no-weight-recorded", "No weight recorded",
                        "Record this patient's weight in vitals before calculating a weight-based dose."));
        BigDecimal totalDoseMg = weightKg.multiply(mgPerKg).setScale(2, RoundingMode.HALF_UP);
        return new DoseCalculationResponse(weightKg, mgPerKg, totalDoseMg, DOSE_RULE_SOURCE);
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
    public PrescriptionResponse upsert(UUID tenantId, UUID queueEntryId, UUID callerStaffId, String ipAddress,
                                        List<PrescriptionItemRequest> items) {
        tenantContext.set(tenantId);
        QueueEntryOwner owner = repo.findQueueEntryOwner(tenantId, queueEntryId).orElseThrow(this::notFound);

        UUID prescriptionId = repo.ensureDraft(tenantId, queueEntryId, owner.patientId(), owner.doctorId());
        PrescriptionRow current = repo.findByQueueEntry(tenantId, queueEntryId).orElseThrow(this::notFound);
        if ("signed".equals(current.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "prescription-signed", "Prescription already signed",
                    "This prescription is signed and can no longer be edited.");
        }

        List<AllergyRow> allergies = allergyRepo.findActiveByPatient(tenantId, owner.patientId());
        for (PrescriptionItemRequest item : items) {
            Optional<AllergyRow> match = findMatch(allergies, item.drugName());
            if (match.isEmpty() || !"severe".equals(match.get().severity())) {
                continue; // no match, or moderate/mild — warning-only, not blocking (NB-107)
            }
            if (item.allergyOverrideReason() == null || item.allergyOverrideReason().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "allergy-conflict", "Allergy conflict",
                        "'" + item.drugName() + "' matches a recorded allergy. Provide allergyOverrideReason to prescribe anyway.");
            }
            ActorInfo actor = repo.findActorInfo(tenantId, callerStaffId).orElseThrow(this::notFound);
            auditService.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), ipAddress,
                    "prescription.allergy_override", "prescription", prescriptionId, null, Map.of(
                            "drugName", item.drugName(), "matchedSubstance", match.get().substance(),
                            "severity", match.get().severity(), "overrideReason", item.allergyOverrideReason()));
        }

        repo.replaceItems(tenantId, prescriptionId, items.stream()
                .map(i -> new PrescriptionItemRow(null, prescriptionId, i.drugName(), i.dosage(), i.frequency(),
                        i.duration(), i.instructions(), i.allergyOverrideReason(), 0))
                .toList());
        return toResponse(tenantId, repo.findByQueueEntry(tenantId, queueEntryId).orElseThrow(this::notFound));
    }

    /** NB-114: a doctor's saved combo of drugs — one PATCH from a set drops its items onto the pad,
     * re-running the exact same allergy/pregnancy/controlled checks as any manually typed item. */
    @Transactional
    public FavouriteRxSetResponse createSet(UUID tenantId, UUID doctorId, FavouriteRxSetRequest req) {
        tenantContext.set(tenantId);
        UUID setId = repo.insertSet(tenantId, doctorId, req.name());
        repo.insertSetItems(tenantId, setId, req.items());
        return toSetResponse(tenantId, repo.findSet(tenantId, setId).orElseThrow(this::notFound));
    }

    @Transactional
    public List<FavouriteRxSetResponse> listSets(UUID tenantId, UUID doctorId) {
        tenantContext.set(tenantId);
        return repo.listSets(tenantId, doctorId).stream().map(s -> toSetResponse(tenantId, s)).toList();
    }

    @Transactional
    public PrescriptionResponse applySet(UUID tenantId, UUID queueEntryId, UUID setId, UUID callerStaffId, String ipAddress) {
        tenantContext.set(tenantId);
        repo.findSet(tenantId, setId).orElseThrow(this::notFound);
        List<PrescriptionItemRequest> items = repo.findSetItems(tenantId, setId).stream()
                .map(i -> new PrescriptionItemRequest(i.drugName(), i.dosage(), i.frequency(), i.duration(),
                        i.instructions(), null))
                .toList();
        return upsert(tenantId, queueEntryId, callerStaffId, ipAddress, items);
    }

    private FavouriteRxSetResponse toSetResponse(UUID tenantId, FavouriteSetRow s) {
        List<FavouriteRxSetItemResponse> items = repo.findSetItems(tenantId, s.id()).stream()
                .map(i -> new FavouriteRxSetItemResponse(i.drugName(), i.dosage(), i.frequency(), i.duration(),
                        i.instructions()))
                .toList();
        return new FavouriteRxSetResponse(s.id(), s.doctorId(), s.name(), s.createdAt(), items);
    }

    @Transactional
    public PrescriptionResponse sign(UUID tenantId, UUID queueEntryId) {
        tenantContext.set(tenantId);
        PrescriptionRow row = repo.findByQueueEntry(tenantId, queueEntryId).orElseThrow(this::notFound);
        repo.sign(tenantId, row.id());
        return toResponse(tenantId, repo.findByQueueEntry(tenantId, queueEntryId).orElseThrow(this::notFound));
    }

    private PrescriptionResponse toResponse(UUID tenantId, PrescriptionRow p) {
        List<AllergyRow> allergies = allergyRepo.findActiveByPatient(tenantId, p.patientId());
        PrescriptionRepository.PatientProfile profile = repo.findPatientProfile(tenantId, p.patientId()).orElse(null);
        String region = repo.findTenantRegion(tenantId).orElse("IN");
        List<PrescriptionItemResponse> items = repo.findItems(tenantId, p.id()).stream()
                .map(i -> {
                    Optional<AllergyRow> match = findMatch(allergies, i.drugName());
                    String allergyWarning = match.map(a -> a.substance() + " (" + a.severity() + ")").orElse(null);
                    String pregnancyWarning = profile == null ? null
                            : RxSafetyChecks.pregnancyWarning(i.drugName(), profile.gender(), profile.dob());
                    String controlledWarning = RxSafetyChecks.controlledSubstanceWarning(i.drugName(), region);
                    return new PrescriptionItemResponse(i.id(), i.drugName(), i.dosage(), i.frequency(), i.duration(),
                            i.instructions(), i.allergyOverrideReason(), i.displayOrder(), allergyWarning,
                            pregnancyWarning, controlledWarning);
                })
                .toList();
        return new PrescriptionResponse(p.id(), p.queueEntryId(), p.patientId(), p.doctorId(), p.status(),
                p.createdAt(), p.signedAt(), items);
    }

    private Optional<AllergyRow> findMatch(List<AllergyRow> allergies, String drugName) {
        String drugLower = drugName.toLowerCase();
        return allergies.stream().filter(a -> drugLower.contains(a.substance().toLowerCase())).findFirst();
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }
}
