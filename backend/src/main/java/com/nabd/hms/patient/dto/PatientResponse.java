package com.nabd.hms.patient.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.UUID;

/** matchedOn (OPS-009): only on search results: "phone", "mrn" or "name". */
public record PatientResponse(UUID id, String mrn, String name, String phone, LocalDate dob,
                               String gender, String status, boolean isMinor, @JsonInclude(JsonInclude.Include.NON_NULL) String matchedOn) {

    public PatientResponse(UUID id, String mrn, String name, String phone, LocalDate dob, String gender, String status, boolean isMinor) {
        this(id, mrn, name, phone, dob, gender, status, isMinor, null);
    }

    public PatientResponse withMatchedOn(String m) {
        return new PatientResponse(id, mrn, name, phone, dob, gender, status, isMinor, m);
    }
}
