package com.nabd.hms.shared;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** PLT-029: the part of the secure upload pipeline other modules may call (the clinical attachments, PLT-030). Bound tenant only. */
public interface FileStore {

    record FileInfo(UUID fileId, String status, String contentType, long sizeBytes, UUID patientId, Instant createdAt) {
    }

    record SignedUrl(String url, Instant expiresAt) {
    }

    /** Empty when the file does not exist for the bound tenant. */
    Optional<FileInfo> info(UUID fileId);

    /** Binds an available file to a patient and visit; false when it is not available or belongs to another patient. */
    boolean bind(UUID fileId, UUID patientId, UUID visitId);

    /** A single-object GET URL valid for at most five minutes; only for a file whose status is 'available'. */
    SignedUrl downloadUrl(UUID fileId);
}
