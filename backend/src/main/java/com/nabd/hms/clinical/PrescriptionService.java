package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.PrescriptionItemRequest;
import com.nabd.hms.clinical.dto.PrescriptionItemResponse;
import com.nabd.hms.clinical.dto.PrescriptionResponse;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.clinical.AllergyModels.AllergyRow;
import static com.nabd.hms.clinical.NoteModels.QueueEntryOwner;
import static com.nabd.hms.clinical.PrescriptionModels.ActorInfo;
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

    private final PrescriptionRepository repo;
    private final AllergyRepository allergyRepo;
    private final AuditService auditService;
    private final TenantContext tenantContext;

    PrescriptionService(PrescriptionRepository repo, AllergyRepository allergyRepo, AuditService auditService,
                         TenantContext tenantContext) {
        this.repo = repo;
        this.allergyRepo = allergyRepo;
        this.auditService = auditService;
        this.tenantContext = tenantContext;
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

    @Transactional
    public PrescriptionResponse sign(UUID tenantId, UUID queueEntryId) {
        tenantContext.set(tenantId);
        PrescriptionRow row = repo.findByQueueEntry(tenantId, queueEntryId).orElseThrow(this::notFound);
        repo.sign(tenantId, row.id());
        return toResponse(tenantId, repo.findByQueueEntry(tenantId, queueEntryId).orElseThrow(this::notFound));
    }

    private PrescriptionResponse toResponse(UUID tenantId, PrescriptionRow p) {
        List<AllergyRow> allergies = allergyRepo.findActiveByPatient(tenantId, p.patientId());
        List<PrescriptionItemResponse> items = repo.findItems(tenantId, p.id()).stream()
                .map(i -> {
                    Optional<AllergyRow> match = findMatch(allergies, i.drugName());
                    String warning = match.map(a -> a.substance() + " (" + a.severity() + ")").orElse(null);
                    return new PrescriptionItemResponse(i.id(), i.drugName(), i.dosage(), i.frequency(), i.duration(),
                            i.instructions(), i.allergyOverrideReason(), i.displayOrder(), warning);
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
