package com.nabd.hms.patient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

final class PatientModels {
    private PatientModels() {
    }

    record PatientRow(UUID id, UUID tenantId, String mrn, String name, String phone, LocalDate dob,
                       String gender, UUID guardianId, String address, String status, Instant createdAt) {
    }

    record MatchCandidateRow(UUID id, String name, String phone, double matchScore) {
    }

    /** NB-085/NB-127-style audit snapshot — same small per-module record as Dental/Prescription/Reports. */
    record ActorInfo(String name, String role) {
    }
}
