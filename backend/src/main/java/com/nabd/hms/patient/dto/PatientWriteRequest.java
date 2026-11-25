package com.nabd.hms.patient.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

public record PatientWriteRequest(
        @NotBlank String name,
        @NotBlank @JsonAlias("mobile") String phone,
        // Required (400, field dob) unless source is paper_import, which sends yearOfBirth instead; checked in PatientService.
        @Past LocalDate dob,
        @NotBlank @Pattern(regexp = "male|female|other") String gender,
        UUID guardianId,
        String address,
        String nationalId,
        // Set after staff reviewed the duplicate candidates a first attempt returned and confirmed
        // this is a different person (e.g. a child on a parent's number). Absent = old behaviour.
        Boolean confirmedNotDuplicate,
        // CGV-005: evidence that the guardian agreed; required whenever guardianId is supplied.
        GuardianConsent guardianConsent,
        // ADM-002: true marks a demo patient; only these may be created while the clinic's data gate is demo_only.
        Boolean synthetic,
        // ADM-014: 'paper_import' = typed from a paper card: yearOfBirth instead of dob, optional note (last visit and doctor).
        @Pattern(regexp = "desk|paper_import") String source,
        Integer yearOfBirth,
        @Size(max = 500) String note,
        // PLT-024: true = the patient chose at registration not to be found from the brand's other branches.
        Boolean brandDirectoryOptOut
) {
    public record GuardianConsent(Boolean confirmed, String channel) {
    }
}
