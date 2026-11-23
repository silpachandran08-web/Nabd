package com.nabd.hms.patient.api;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * IDC-055: the demographics a FHIR Patient needs (record number, name, sex, date of birth). Never the phone, address or the
 * sealed national id. A system read: the calling flow authorises and records the disclosure. Runs in the caller's tenant transaction.
 */
public interface PatientDemographics {

    record Demographics(UUID id, String mrn, String name, String gender, LocalDate dob) {
    }

    /** Empty when the patient is not in the bound tenant. */
    Optional<Demographics> find(UUID patientId);
}
