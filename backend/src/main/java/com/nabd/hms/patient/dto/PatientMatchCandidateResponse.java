package com.nabd.hms.patient.dto;

import java.util.UUID;

public record PatientMatchCandidateResponse(UUID patientId, String name, String phone, double matchScore) {
}
