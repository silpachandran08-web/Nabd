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

    /** Same, valid for {@code ttl} (at most five minutes). IDC-015 asks for 60 seconds. */
    SignedUrl downloadUrl(UUID fileId, java.time.Duration ttl);

    /**
     * FIN-035 / CLN-014: stores a document the server rendered itself (no client upload, so no quarantine or scan) as an available file
     * bound to the patient and visit, so the secure-link pipeline (IDC-015) can share it. Returns the file id.
     */
    UUID storeGenerated(UUID staffId, String purpose, String contentType, byte[] data, UUID patientId, UUID visitId);

    /** The bytes of an available file of the bound tenant; empty when it does not exist or is not available. */
    Optional<byte[]> content(UUID fileId);

    /** PHL-020: a file readable only through the module that owns it. {@link #info} and {@link #bind} do not see it, so no
     * generic path (visit attachment, secure link, signature) can pick it up by id. */
    String PRIVATE_PURPOSE = "paper_rx";

    record UploadSlot(UUID uploadId, String putUrl, long maxBytes) {
    }

    /** PHL-020: the PLT-029 upload slot for a caller that gates the upload with its own permission. Same type, size and scan rules. */
    UploadSlot initUpload(UUID staffId, String purpose, String contentType, long sizeBytes);

    /** Starts the scan of an uploaded slot of exactly this purpose (404 otherwise); returns the file id. */
    UUID completeUpload(UUID uploadId, String purpose);

    /** The scan status of a file of the bound tenant with exactly this purpose; empty when there is none. */
    Optional<String> statusOf(UUID fileId, String purpose);
}
