package com.nabd.hms.patient.api;

import java.util.Optional;
import java.util.UUID;

/** IDC-017: the least another module may ask about a patient of the bound tenant (RLS): that it exists and how to show it in a merge prompt. */
public interface PatientSummaries {

    record Summary(UUID id, String name, String mrn) {
    }

    /** Empty when the patient is not in the bound tenant or has been merged away. Call inside a tenant transaction. */
    Optional<Summary> find(UUID patientId);
}
