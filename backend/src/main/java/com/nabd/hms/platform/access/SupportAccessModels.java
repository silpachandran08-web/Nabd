package com.nabd.hms.platform.access;

import java.time.Instant;
import java.util.UUID;

final class SupportAccessModels {

    private SupportAccessModels() {
    }

    /** status: requested | active | declined | expired | revoked (approved is a transient name the schema allows but nothing stores).
     * expiresAt is when the grant stops being usable: the request deadline while requested, the window end once active. */
    record Grant(UUID id, UUID tenantId, UUID ticketId, String scope, UUID operatorId, String reason, Instant grantedAt,
                 Instant expiresAt, Instant revokedAt, String status, Integer minutes, UUID decidedByStaffId,
                 Instant decidedAt, String declineNote, Instant windowStartedAt, Instant windowEndsAt) {
        /** The only test that opens patient data: owner-approved, not revoked, inside the window (checked on every call). */
        boolean isActive(Instant now) {
            return "active".equals(status) && revokedAt == null && windowEndsAt != null && now.isBefore(windowEndsAt);
        }

        /** What a reader should see: a request or window whose time ran out reads expired even before the sweep job ran. */
        String effectiveStatus(Instant now) {
            boolean open = "requested".equals(status) || "active".equals(status);
            return open && !now.isBefore(expiresAt) ? "expired" : status;
        }
    }

    record OperatorInfo(String name, String role) {
    }

    /** SSA-04 / PLT-057: the only patient columns the repository reads for support. No clinical column is selected,
     * and the response type carries initials, masked phone, birth year and MRN only. */
    record RedactedPatient(String mrn, String name, String phone, java.time.LocalDate dob) {
    }
}
