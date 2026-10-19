package com.nabd.hms.staff.dto;

import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.UUID;

/** All fields optional — a PATCH only changes what's present; StaffService merges onto the current row. */
public record StaffPatchRequest(
        UUID roleId,
        @Pattern(regexp = "own_patients_only|all_clinic_patients") String scope,
        List<String> fieldGrants,
        UUID departmentId,
        /** DOC-N1: null = leave unchanged; an empty string clears it; otherwise 1-40 of letters, digits, space . / - */
        @Pattern(regexp = "|[A-Za-z0-9][A-Za-z0-9 ./-]{0,39}") String professionalRegistrationNo,
        /** PLT-016: null = unchanged (but recording a new registration number sets it); true needs a registration number. */
        Boolean clinician
) {
}
