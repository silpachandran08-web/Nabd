package com.nabd.hms.platform.access;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

final class SupportAccessModels {

    private SupportAccessModels() {
    }

    record Grant(UUID id, UUID tenantId, UUID operatorId, String reason, Instant grantedAt,
                 Instant expiresAt, Instant revokedAt) {
        boolean isActive(Instant now) {
            return revokedAt == null && now.isBefore(expiresAt);
        }
    }

    record OperatorInfo(String name, String role) {
    }

    /** Only the fields any staff member already sees via PatientResponse — no clinical fields
     * exist in the data model yet (Clinical Workspace/Charting isn't built), so today this is
     * identical to that view by necessity. The point is the type boundary: whatever clinical
     * fields land on PatientDetailResponse later, this record structurally cannot carry them. */
    record RedactedPatient(UUID id, String mrn, String name, String phone, LocalDate dob, String gender,
                            String status) {
    }
}
