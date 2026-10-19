package com.nabd.hms.staff.dto;

import java.util.UUID;

/** PLT-016: one staff row the V1370 clinician backfill could not decide cleanly, for the owner to confirm via PATCH clinician. */
public record ClinicianReviewEntry(UUID staffId, String name, String roleName, String reason, boolean clinician) {
}
