package com.nabd.hms.department.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/** POST /v1/departments/{id}/workflow — pick a published template by code and set any of the
 * toggles it defines. There is no step array: ordering is owned by the template, not the caller. */
public record DepartmentWorkflowRequest(@NotBlank String templateCode, Map<String, Boolean> toggles) {
}
