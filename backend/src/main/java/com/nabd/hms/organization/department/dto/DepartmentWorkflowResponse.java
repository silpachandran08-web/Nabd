package com.nabd.hms.department.dto;

import java.util.List;
import java.util.Map;

/** GET /v1/departments/{id}/workflow — the department's current pick (null templateCode means
 * "nothing chosen yet, running on the default"), its resolved stage order for preview, and the
 * full template library so the editor can render the picker from one call. */
public record DepartmentWorkflowResponse(String templateCode, Map<String, Boolean> toggles,
                                          List<String> resolvedSteps, List<WorkflowTemplateResponse> availableTemplates) {
}
