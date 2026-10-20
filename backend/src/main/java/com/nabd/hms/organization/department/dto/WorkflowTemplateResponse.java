package com.nabd.hms.department.dto;

import java.util.List;

/** One entry of the platform's template library — what the picker on the department editor
 * offers. steps is the template's own order (before any toggle is applied). */
public record WorkflowTemplateResponse(String code, String name, List<String> steps, List<String> toggleKeys) {
}
