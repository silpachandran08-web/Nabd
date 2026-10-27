package com.nabd.hms.patient.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ACC-038: opaque patient references for the Patient Access Edge. A reference is a random per-tenant token; the Edge never
 * learns a patient id. Call inside a transaction bound to the tenant (TenantScope.run).
 */
public interface PatientRefs {

    /** Household member as the Edge may see it: no date of birth, no full name. {@code age} is an age band ("0-4" .. "60+"); {@code years} is the age in whole years (ACC-042 shows "Aarav (6)"). */
    record HouseholdMember(String patientRef, String firstName, String age, String relation, boolean isGuardian, Integer years) {
    }

    /** The patient's reference, created on first use. */
    String refFor(UUID patientId);

    /** The patient behind a reference of the bound tenant; a merged patient resolves to the record it was merged into. */
    Optional<UUID> patientIdFor(String patientRef);

    /** Active patients registered on the phone (SHA-256 hex of its E.164 digits) plus the dependants of those patients. */
    List<HouseholdMember> household(String phoneHash);
}
