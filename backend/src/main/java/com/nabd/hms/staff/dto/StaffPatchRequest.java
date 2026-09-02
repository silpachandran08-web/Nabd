package com.nabd.hms.staff.dto;

import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.UUID;

/** All fields optional — a PATCH only changes what's present; StaffService merges onto the current row. */
public record StaffPatchRequest(
        UUID roleId,
        @Pattern(regexp = "own_patients_only|all_clinic_patients") String scope,
        List<String> fieldGrants,
        UUID departmentId
) {
}
