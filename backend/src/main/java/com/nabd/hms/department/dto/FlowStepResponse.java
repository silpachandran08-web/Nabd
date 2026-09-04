package com.nabd.hms.department.dto;

/** One entry of a department's *resolved* pipeline, in order — the expansion of its workflow
 * template plus toggles (see DepartmentService.resolveStatusSequence). Read-only: there is no
 * write endpoint for this shape any more, see DepartmentWorkflowRequest for that. */
public record FlowStepResponse(String stepType) {
}
