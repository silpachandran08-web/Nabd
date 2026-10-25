package com.nabd.hms.patient.api;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * ADM-012: what the data-import pipeline may ask of the patient module. Runs in the caller's tenant transaction (RLS);
 * the tenant is never a parameter. Phones are E.164. The caller has already passed the data gate and validated the row.
 */
public interface PatientDirectory {

    /** Which "phone|dob" keys (dob ISO) of an active patient already exist for these phones. */
    Set<String> existingPhoneDobKeys(Collection<String> phonesE164);

    Optional<UUID> findByPhoneDob(String phoneE164, LocalDate dob);

    /** Registers a patient from a legacy list; legacyRecordNo may be null. */
    UUID createImported(String name, String phoneE164, LocalDate dob, String gender, String legacyRecordNo);

    /** Fills the old record number when the patient has none (never overwrites). */
    void mergeImported(UUID patientId, String legacyRecordNo);
}
