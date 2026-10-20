package com.nabd.hms.department;

import java.util.UUID;

final class DepartmentModels {
    private DepartmentModels() {
    }

    record DepartmentRow(UUID id, String name, boolean isDefault, boolean active) {
    }

    record TransferEdgeRow(UUID fromDepartmentId, UUID toDepartmentId) {
    }

    /** A platform-authored workflow template. stepsJson/toggleKeysJson are raw jsonb text —
     * DepartmentService parses them, same split as RoleRepository/RoleService for `grants`. */
    record WorkflowTemplateRow(UUID id, String code, String name, String stepsJson, String toggleKeysJson) {
    }

    /** A department's current template pick plus the toggles it set, joined with the template it
     * points to. Absent entirely when the department hasn't picked a template yet. */
    record WorkflowSelectionRow(UUID workflowDefinitionId, String templateCode, String stepsJson,
                                 String toggleKeysJson, String togglesJson) {
    }
}
