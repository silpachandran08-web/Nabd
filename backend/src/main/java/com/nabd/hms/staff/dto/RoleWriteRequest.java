package com.nabd.hms.staff.dto;

import com.nabd.hms.common.ModuleGrant;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record RoleWriteRequest(
        @NotBlank String name,
        @NotEmpty List<ModuleGrant> grants,
        Boolean mfaRequired // PLT-005: optional; omitted keeps the current value (false on create)
) {
}
