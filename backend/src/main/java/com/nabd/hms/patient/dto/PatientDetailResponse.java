package com.nabd.hms.patient.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * ponytail: allergies/chronicConditions/activePackages/outstandingBalance/lastVisitAt are stubbed
 * (empty/zero/null) — their source tables (Clinical Workspace, Packages, Billing) don't exist yet.
 * Wire these up as each of those epics lands; the shape is already correct per api/openapi.yaml.
 */
public record PatientDetailResponse(UUID id, String mrn, String name, String phone, LocalDate dob,
                                     String gender, String status, List<String> allergies,
                                     List<String> chronicConditions, int activePackages,
                                     double outstandingBalance, Instant lastVisitAt) {
}
