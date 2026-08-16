package com.nabd.hms.staff.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record StaffInviteRequest(
        @NotBlank @Email String email,
        @NotBlank String name,
        @NotBlank String mobilePhone,
        @NotNull java.util.UUID roleId,
        @Pattern(regexp = "own_patients_only|all_clinic_patients") String scope
) {
    public String scopeOrDefault() {
        return scope == null ? "all_clinic_patients" : scope;
    }
}
