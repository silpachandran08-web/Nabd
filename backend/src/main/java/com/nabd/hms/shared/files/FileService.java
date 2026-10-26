package com.nabd.hms.shared.files;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.shared.FileStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** PLT-029: upload slot, complete, scan. A file is 'available' only after type check, antivirus and metadata strip. */
@Service
public class FileService implements FileStore {

    static final Set<String> PURPOSES = Set.of("outside_report", "clinical_photo", "xray", "consent_form");
    static final Set<String> TYPES = Set.of("application/pdf", "image/jpeg", "image/png");
    static final Duration UPLOAD_TTL = Duration.ofMinutes(10);
    static final Duration DOWNLOAD_TTL = Duration.ofMinutes(5);

    public record Slot(UUID uploadId, String putUrl, long maxBytes) {
    }

    private final FileRepository repo;
    private final ObjectStorage storage;
    private final VirusScanner scanner;
    private final FileScanWorker worker;
    private final EventPublisher events;
    private final TenantContext tenantContext;
    private final Clock clock;
    private final long maxBytes;

    FileService(FileRepository repo, ObjectStorage storage, VirusScanner scanner, FileScanWorker worker, EventPublisher events,
                TenantContext tenantContext, Clock clock, @Value("${app.files.max-bytes:20971520}") long maxBytes) {
        this.repo = repo;
        this.storage = storage;
        this.scanner = scanner;
        this.worker = worker;
        this.events = events;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.maxBytes = maxBytes;
    }

    @Transactional
    public Slot init(UUID staffId, String purpose, String contentType, long sizeBytes) {
        if (!PURPOSES.contains(purpose)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-purpose", "Invalid purpose", "Unknown upload purpose.");
        }
        if (!TYPES.contains(contentType)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "TYPE_NOT_ALLOWED", "Type not allowed", "Only PDF, JPEG and PNG files are accepted.");
        }
        if (sizeBytes <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-size", "Invalid size", "sizeBytes must be positive.");
        }
        if (sizeBytes > maxBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "File too large", "The file exceeds the " + maxBytes + " byte limit.");
        }
        UUID tenantId = tenantContext.current();
        UUID id = UUID.randomUUID();
        String quarantineKey = "quarantine/" + tenantId + "/" + id;
        repo.insert(id, tenantId, purpose, quarantineKey, "tenant/" + tenantId + "/" + id, contentType, sizeBytes, staffId);
        return new Slot(id, storage.presign(true, ObjectStorage.Bucket.QUARANTINE, quarantineKey, UPLOAD_TTL, maxBytes), maxBytes);
    }

    /** Starts the scan; the response is 202 pending_scan. */
    @Transactional
    public UUID complete(UUID id, String clientSha256) {
        UUID tenantId = tenantContext.current();
        FileRepository.Row f = repo.find(tenantId, id).orElseThrow(FileService::notFound);
        if (!"awaiting_upload".equals(f.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_COMPLETED", "Already completed", "This upload was already completed.");
        }
        byte[] data = storage.get(ObjectStorage.Bucket.QUARANTINE, f.quarantineKey())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "UPLOAD_MISSING", "Upload missing", "No bytes were uploaded to this slot."));
        byte[] sha = sha256(data);
        if (clientSha256 != null && !clientSha256.equalsIgnoreCase(HexFormat.of().formatHex(sha))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CHECKSUM_MISMATCH", "Checksum mismatch", "The uploaded bytes do not match sha256.");
        }
        if (!repo.markPendingScan(tenantId, id, sha)) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_COMPLETED", "Already completed", "This upload was already completed.");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                worker.submit(tenantId, id);
            }
        });
        return id;
    }

    /** Called by the worker inside TenantScope. Idempotent: only a pending_scan row changes. */
    @Transactional
    void scan(UUID id) {
        UUID tenantId = tenantContext.current();
        FileRepository.Row f = repo.find(tenantId, id).orElse(null);
        if (f == null || !"pending_scan".equals(f.status())) {
            return;
        }
        byte[] data = storage.get(ObjectStorage.Bucket.QUARANTINE, f.quarantineKey()).orElse(null);
        if (data == null) {
            return;
        }
        String detected = FileSniffer.detect(data);
        String status;
        if (detected == null || !detected.equals(f.declaredType())) {
            status = "rejected_type";
        } else if (scanner.infected(data)) {
            status = "infected";
        } else {
            status = "available";
        }
        if ("available".equals(status)) {
            byte[] clean = "image/jpeg".equals(detected) ? FileSniffer.stripJpegMetadata(data) : data;
            storage.put(ObjectStorage.Bucket.CLEAN, f.storageKey(), clean);
            storage.delete(ObjectStorage.Bucket.QUARANTINE, f.quarantineKey());
            repo.finish(tenantId, id, status, detected, clean.length, sha256(clean), scanner.engine(), clock.instant());
        } else {
            repo.finish(tenantId, id, status, detected, f.sizeBytes(), sha256(data), scanner.engine(), clock.instant());
            repo.alertOwners(tenantId, id, status);
        }
        events.publish(tenantId, DomainEventType.ATTACHMENT_SCANNED, id, Map.of("fileId", id, "purpose", f.purpose(), "status", status));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FileInfo> info(UUID fileId) {
        return repo.find(tenantContext.current(), fileId)
                .map(f -> new FileInfo(f.id(), f.status(), f.detectedType() != null ? f.detectedType() : f.declaredType(), f.sizeBytes(), f.patientId(), f.createdAt()));
    }

    @Override
    @Transactional
    public boolean bind(UUID fileId, UUID patientId, UUID visitId) {
        return repo.bindPatient(tenantContext.current(), fileId, patientId, visitId);
    }

    @Override
    @Transactional(readOnly = true)
    public SignedUrl downloadUrl(UUID fileId) {
        FileRepository.Row f = repo.find(tenantContext.current(), fileId).filter(r -> "available".equals(r.status())).orElseThrow(FileService::notFound);
        return new SignedUrl(storage.presign(false, ObjectStorage.Bucket.CLEAN, f.storageKey(), DOWNLOAD_TTL, 0), clock.instant().plus(DOWNLOAD_TTL));
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }

    private static byte[] sha256(byte[] d) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
