package com.nabd.hms.department.dto;

import jakarta.validation.constraints.NotBlank;

/** `active` is ignored on create (a department always starts active) and applied on update. */
public record DepartmentWriteRequest(@NotBlank String name, boolean requiresVitals, boolean active) {
}
