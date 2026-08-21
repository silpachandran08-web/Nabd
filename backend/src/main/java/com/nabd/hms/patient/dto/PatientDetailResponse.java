package com.nabd.hms.patient.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * ponytail: chronicConditions/activePackages/outstandingBalance are still stubbed (empty/zero) —
 * their source tables (problem list, Packages, Billing) don't exist yet. lastVisitAt is real
 * (queue_entries.updated_at where status='completed', NB-074); allergies is real (patient_allergies,
 * NB-107/108).
 */
public record PatientDetailResponse(UUID id, String mrn, String name, String phone, LocalDate dob,
                                     String gender, String status, List<String> allergies,
                                     List<String> chronicConditions, int activePackages,
                                     double outstandingBalance, Instant lastVisitAt) {
}
