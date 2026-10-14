package com.nabd.hms.patient.dto;

import java.util.List;
import java.util.UUID;

/**
 * One likely duplicate returned with the 409 on registration.
 *
 * <p>matchScore is 0..1 (not a percentage): 1.0 when the mobile number matches (digits only), otherwise the
 * trigram similarity of the names when the date of birth matches. matchedFields (REC-038) names the compared
 * fields that agreed: "phone" (same digits), "dob" (same date) and "name" (trigram similarity above 0.3), in
 * that order. The national ID is never compared and never returned here.
 */
public record PatientMatchCandidateResponse(UUID patientId, String name, String phone, double matchScore,
                                            List<String> matchedFields) {
}
