package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.PrescriptionItemRequest;
import com.nabd.hms.clinical.dto.PrescriptionItemResponse;
import com.nabd.hms.clinical.dto.PrescriptionResponse;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static com.nabd.hms.clinical.AllergyModels.AllergyRow;
import static com.nabd.hms.clinical.NoteModels.QueueEntryOwner;
import static com.nabd.hms.clinical.PrescriptionModels.PrescriptionItemRow;
import static com.nabd.hms.clinical.PrescriptionModels.PrescriptionRow;

/**
 * NB-109/105: free-text drug pad, no coded formulary (NB-010 doesn't exist). NB-108's hard warning
 * is a case-insensitive substring match against the patient's own recorded allergy substances — not
 * a real drug-interaction database, which this app has no source for. An item that matches requires
 * an explicit allergyOverrideReason to save.
 */
@Service
public class PrescriptionService {

    private final PrescriptionRepository repo;
    private final AllergyRepository allergyRepo;
    private final TenantContext tenantContext;

    PrescriptionService(PrescriptionRepository repo, AllergyRepository allergyRepo, TenantContext tenantContext) {
        this.repo = repo;
        this.allergyRepo = allergyRepo;
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
    public PrescriptionResponse upsert(UUID tenantId, UUID queueEntryId, List<PrescriptionItemRequest> items) {
        tenantContext.set(tenantId);
        QueueEntryOwner owner = repo.findQueueEntryOwner(tenantId, queueEntryId).orElseThrow(this::notFound);

        List<String> allergySubstances = allergyRepo.findActiveByPatient(tenantId, owner.patientId()).stream()
                .map(AllergyRow::substance).map(String::toLowerCase).toList();
        for (PrescriptionItemRequest item : items) {
            String drugLower = item.drugName().toLowerCase();
            boolean conflict = allergySubstances.stream().anyMatch(drugLower::contains);
            if (conflict && (item.allergyOverrideReason() == null || item.allergyOverrideReason().isBlank())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "allergy-conflict", "Allergy conflict",
                        "'" + item.drugName() + "' matches a recorded allergy. Provide allergyOverrideReason to prescribe anyway.");
            }
        }

        UUID prescriptionId = repo.ensureDraft(tenantId, queueEntryId, owner.patientId(), owner.doctorId());
        PrescriptionRow current = repo.findByQueueEntry(tenantId, queueEntryId).orElseThrow(this::notFound);
        if ("signed".equals(current.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "prescription-signed", "Prescription already signed",
                    "This prescription is signed and can no longer be edited.");
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
        List<PrescriptionItemResponse> items = repo.findItems(tenantId, p.id()).stream()
                .map(i -> new PrescriptionItemResponse(i.id(), i.drugName(), i.dosage(), i.frequency(), i.duration(),
                        i.instructions(), i.allergyOverrideReason(), i.displayOrder()))
                .toList();
        return new PrescriptionResponse(p.id(), p.queueEntryId(), p.patientId(), p.doctorId(), p.status(),
                p.createdAt(), p.signedAt(), items);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }
}
