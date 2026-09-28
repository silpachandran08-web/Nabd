package com.nabd.hms.patient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;
import java.util.UUID;

public record PatientWriteRequest(
        @NotBlank String name,
        @NotBlank String phone,
        @NotNull @Past LocalDate dob,
        @NotBlank @Pattern(regexp = "male|female|other") String gender,
        UUID guardianId,
        String address,
        String nationalId,
        // Set after staff reviewed the duplicate candidates a first attempt returned and confirmed
        // this is a different person (e.g. a child on a parent's number). Absent = old behaviour.
        Boolean confirmedNotDuplicate
) {
}
