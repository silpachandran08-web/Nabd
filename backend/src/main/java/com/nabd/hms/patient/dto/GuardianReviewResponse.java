package com.nabd.hms.patient.dto;

import java.time.LocalDate;
import java.util.UUID;

/** NB-082: one row of the "guardian access review due" worklist — a patient who has turned 18 but
 * still has a guardian on file. */
public record GuardianReviewResponse(UUID patientId, String mrn, String patientName, LocalDate dob,
                                      UUID guardianId, String guardianName) {
}
