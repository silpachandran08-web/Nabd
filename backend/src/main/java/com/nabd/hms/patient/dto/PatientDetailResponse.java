package com.nabd.hms.patient.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * activePackages is always 0: the packages module exists (PackageInstanceService) but this response does not count a
 * patient's instances yet (PLT-034 / T-202: comment corrected, value unchanged). lastVisit (LIVE-03) is
 * the registry's definition — latest queue_date of a non-no-show queue entry — so the drawer and the
 * registry list agree; lastVisitAt (deprecated) is the older completed-entry timestamp (NB-074); allergies is real
 * (patient_allergies, NB-107/108); chronicConditions is real (chronic_conditions, NB-077).
 *
 * outstandingBalance is null (omitted from the JSON entirely — NB-052 requires the field be
 * absent, not merely zeroed or hidden client-side) unless the caller's field grants include
 * "financial" or carry no custom restriction at all. When present it is still 0.0: this response does
 * not aggregate the patient's billing dues. NB-052 governs whether it's visible, not its accuracy.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PatientDetailResponse(UUID id, String mrn, String name, String phone, LocalDate dob,
                                     String gender, String status, List<String> allergies,
                                     List<String> chronicConditions, int activePackages,
                                     Double outstandingBalance, Instant lastVisitAt, boolean isMinor,
                                     UUID guardianId, String guardianName, Instant guardianConsentGrantedAt,
                                     Instant guardianReviewKeptAt, LocalDate lastVisit) {
}
