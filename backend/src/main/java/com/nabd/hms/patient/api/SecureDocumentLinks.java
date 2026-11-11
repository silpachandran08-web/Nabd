package com.nabd.hms.patient.api;

import java.time.Instant;
import java.util.UUID;

/**
 * FIN-035: an OTP-protected secure link (IDC-015) to an available file of the bound tenant, sent to the patient or their guardian.
 * Same rules as POST /v1/secure-links: the file must be bound to a processable patient and the recipient must be that patient,
 * their guardian or a GUARDIAN-scope caregiver (403 recipient-not-allowed otherwise). Runs in the caller's transaction.
 */
public interface SecureDocumentLinks {

    record Issued(UUID linkId, Instant expiresAt) {
    }

    Issued issueForFile(UUID staffId, String ip, UUID fileId, UUID recipientPatientId, String language);
}
