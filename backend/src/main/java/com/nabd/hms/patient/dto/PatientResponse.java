package com.nabd.hms.patient.dto;

import java.time.LocalDate;
import java.util.UUID;

public record PatientResponse(UUID id, String mrn, String name, String phone, LocalDate dob,
                               String gender, String status) {
}
