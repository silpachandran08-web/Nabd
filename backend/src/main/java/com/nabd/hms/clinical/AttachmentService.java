package com.nabd.hms.clinical;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.shared.FileStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** PLT-030: attaches a scanned file to a visit. The read side (list, signed URL) is gated and logged by AttachmentController. */
@Service
class AttachmentService {

    private final AttachmentRepository repo;
    private final FileStore files;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final TenantContext tenantContext;

    AttachmentService(AttachmentRepository repo, FileStore files, AuditService audit, ActorDirectory actors, TenantContext tenantContext) {
        this.repo = repo;
        this.files = files;
        this.audit = audit;
        this.actors = actors;
        this.tenantContext = tenantContext;
    }

    @Transactional
    UUID attach(UUID visitId, UUID fileId, String kind, String note, UUID staffId, String ip) {
        UUID tenantId = tenantContext.current();
        UUID patientId = repo.visitPatient(tenantId, visitId).orElseThrow(AttachmentService::notFound);
        FileStore.FileInfo f = files.info(fileId).orElseThrow(AttachmentService::notFound);
        if (!"available".equals(f.status()) || !files.bind(fileId, patientId, visitId)) {
            throw new ApiException(HttpStatus.CONFLICT, "FILE_NOT_AVAILABLE", "File not available", "The file is not scanned and clean, or belongs to another patient.");
        }
        UUID id;
        try {
            id = repo.insert(tenantId, visitId, patientId, fileId, kind, note, staffId);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "FILE_NOT_AVAILABLE", "File not available", "The file is already attached to this visit.");
        }
        ActorDirectory.Actor actor = actors.findActor(tenantId, staffId).orElseThrow(AttachmentService::notFound);
        audit.record(tenantId, "staff", staffId, actor.name(), actor.role(), ip, "attachment.added", "visit_attachment", id, null,
                Map.of("kind", kind, "fileId", fileId.toString())); // ids and kind only: the note is free text
        return id;
    }

    @Transactional
    java.util.List<AttachmentController.Item> list(UUID visitId) {
        return repo.list(tenantContext.current(), visitId).stream().map(r -> new AttachmentController.Item(r.id(), r.patientId(), r.fileId(), r.kind(), r.note(),
                r.createdAt(), files.info(r.fileId()).map(FileStore.FileInfo::contentType).orElse(null))).toList();
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }
}
