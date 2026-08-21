package com.nabd.hms.patient.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * ponytail: allergies/chronicConditions/activePackages/outstandingBalance are still stubbed
 * (empty/zero) — their source tables (allergy register, problem list, Packages, Billing) don't
 * exist yet. lastVisitAt is real now (queue_entries.updated_at where status='completed', NB-074).
 */
public record PatientDetailResponse(UUID id, String mrn, String name, String phone, LocalDate dob,
                                     String gender, String status, List<String> allergies,
                                     List<String> chronicConditions, int activePackages,
                                     double outstandingBalance, Instant lastVisitAt) {
}
