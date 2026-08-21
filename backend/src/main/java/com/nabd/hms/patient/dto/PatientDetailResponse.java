package com.nabd.hms.patient.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * ponytail: activePackages is still stubbed (zero) — Packages doesn't exist yet. lastVisitAt is
 * real (queue_entries.updated_at where status='completed', NB-074); allergies is real
 * (patient_allergies, NB-107/108); chronicConditions is real (chronic_conditions, NB-077).
 *
 * outstandingBalance is null (omitted from the JSON entirely — NB-052 requires the field be
 * absent, not merely zeroed or hidden client-side) unless the caller's field grants include
 * "financial" or carry no custom restriction at all. Still a stub value (0.0) when present —
 * Billing dues aggregation doesn't exist yet — NB-052 governs whether it's visible, not its
 * accuracy.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PatientDetailResponse(UUID id, String mrn, String name, String phone, LocalDate dob,
                                     String gender, String status, List<String> allergies,
                                     List<String> chronicConditions, int activePackages,
                                     Double outstandingBalance, Instant lastVisitAt) {
}
