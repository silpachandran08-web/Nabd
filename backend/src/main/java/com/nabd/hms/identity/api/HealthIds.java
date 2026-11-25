package com.nabd.hms.identity.api;

import java.util.Optional;
import java.util.UUID;

/**
 * IDC-055: the national health identifiers a FHIR Patient and Organization carry. The ABHA values are unsealed here, in
 * memory, for the exchange mapping only: never log or store what this returns. Aadhaar is not an identifier type and is
 * never returned. A system read: the calling flow authorises and records the disclosure. Runs in the caller's tenant transaction.
 */
public interface HealthIds {

    /** @param abhaNumber 14 digits, null when none is linked; @param abhaAddress lower case, null when none is linked */
    record PatientIds(String abhaNumber, String abhaAddress) {
    }

    PatientIds ofPatient(UUID patientId);

    /** The clinic's Health Facility Registry id, once verified. */
    Optional<String> facilityHfrId();
}
