package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.NoteAmendmentRequest;
import com.nabd.hms.clinical.dto.NoteAmendmentResponse;
import com.nabd.hms.clinical.dto.NoteResponse;
import com.nabd.hms.clinical.dto.NoteUpsertRequest;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static com.nabd.hms.clinical.NoteModels.AmendmentRow;
import static com.nabd.hms.clinical.NoteModels.ActorInfo;
import static com.nabd.hms.clinical.NoteModels.NoteRow;
import static com.nabd.hms.clinical.NoteModels.QueueEntryOwner;

/**
 * NB-103: structured SOAP fields, not a configurable per-specialty template — that's NB-121's
 * specialty framework. Autosave is the frontend calling upsert() every 10s while status is draft;
 * signing (on "complete consultation") locks the note the same way TenantLifecycleService locks a
 * terminal state — no amendment path here, that's NB-104.
 */
@Service
public class NoteService {

    private static final Logger log = LoggerFactory.getLogger(NoteService.class);

    private final NoteRepository repo;
    private final AuditService auditService;
    private final TenantContext tenantContext;

    NoteService(NoteRepository repo, AuditService auditService, TenantContext tenantContext) {
        this.repo = repo;
        this.auditService = auditService;
        this.tenantContext = tenantContext;
    }

    @Transactional
    public NoteResponse get(UUID tenantId, UUID queueEntryId) {
        tenantContext.set(tenantId);
        return repo.findByQueueEntry(tenantId, queueEntryId).map(this::toResponse).orElseThrow(this::notFound);
    }

    @Transactional
    public NoteResponse upsert(UUID tenantId, UUID queueEntryId, NoteUpsertRequest req) {
        tenantContext.set(tenantId);
        QueueEntryOwner owner = repo.findQueueEntryOwner(tenantId, queueEntryId).orElseThrow(this::notFound);

        int updated = repo.upsert(tenantId, queueEntryId, owner.patientId(), owner.doctorId(),
                req.subjective(), req.objective(), req.assessment(), req.plan(), req.diagnosis());
        // A fresh insert always affects a row; 0 only happens when a signed row already blocked the update.
        if (updated == 0) {
            throw noteSigned();
        }
        return repo.findByQueueEntry(tenantId, queueEntryId).map(this::toResponse).orElseThrow(this::notFound);
    }

    @Transactional
    public NoteResponse sign(UUID tenantId, UUID queueEntryId) {
        tenantContext.set(tenantId);
        repo.findByQueueEntry(tenantId, queueEntryId).orElseThrow(this::notFound);
        repo.sign(tenantId, queueEntryId);
        log.info("clinical note signed for queue entry {} (tenant {})", queueEntryId, tenantId);
        return repo.findByQueueEntry(tenantId, queueEntryId).map(this::toResponse).orElseThrow(this::notFound);
    }

    // ── amendments (NB-104) ──────────────────────────────────────────────
    // Only for a signed note — a draft is still directly editable via upsert(); amending a draft
    // would just be a confusing second way to do the same thing.

    @Transactional
    public NoteAmendmentResponse amend(UUID tenantId, UUID callerStaffId, UUID queueEntryId, NoteAmendmentRequest req) {
        tenantContext.set(tenantId);
        NoteRow note = repo.findByQueueEntry(tenantId, queueEntryId).orElseThrow(this::notFound);
        if (!"signed".equals(note.status())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "note-not-signed", "Note not signed",
                    "Only a signed note can be amended — edit the draft directly instead.");
        }
        UUID id = repo.insertAmendment(tenantId, note.id(), callerStaffId, req.reason(), req.subjective(),
                req.objective(), req.assessment(), req.plan(), req.diagnosis());
        ActorInfo actor = repo.findActorInfo(tenantId, callerStaffId).orElseThrow(this::notFound);
        auditService.record(tenantId, "staff", callerStaffId, actor.name(), actor.role(), null,
                "clinical_note.amended", "clinical_note", note.id(), null, req);
        log.info("clinical note {} amended (amendment {}) by {}: {}", note.id(), id, callerStaffId, req.reason());
        return repo.listAmendments(tenantId, note.id()).stream()
                .filter(a -> a.id().equals(id)).findFirst().map(this::toAmendmentResponse).orElseThrow();
    }

    @Transactional
    public List<NoteAmendmentResponse> listAmendments(UUID tenantId, UUID queueEntryId) {
        tenantContext.set(tenantId);
        NoteRow note = repo.findByQueueEntry(tenantId, queueEntryId).orElseThrow(this::notFound);
        return repo.listAmendments(tenantId, note.id()).stream().map(this::toAmendmentResponse).toList();
    }

    private NoteAmendmentResponse toAmendmentResponse(AmendmentRow a) {
        return new NoteAmendmentResponse(a.id(), a.amendedBy(), a.reason(), a.subjective(), a.objective(),
                a.assessment(), a.plan(), a.diagnosis(), a.createdAt());
    }

    private NoteResponse toResponse(NoteRow n) {
        return new NoteResponse(n.id(), n.queueEntryId(), n.patientId(), n.doctorId(), n.subjective(),
                n.objective(), n.assessment(), n.plan(), n.diagnosis(), n.status(), n.signedAt(), n.updatedAt());
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }

    private ApiException noteSigned() {
        return new ApiException(HttpStatus.CONFLICT, "note-signed", "Note already signed",
                "This clinical note is signed and can no longer be edited.");
    }
}
