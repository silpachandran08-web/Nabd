package com.nabd.hms.patient.dto;

import java.util.List;

public record DuplicateCandidatesResponse(List<PatientMatchCandidateResponse> candidates) {
}
