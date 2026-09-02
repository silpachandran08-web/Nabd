package com.nabd.hms.department;

import java.util.UUID;

final class DepartmentModels {
    private DepartmentModels() {
    }

    record DepartmentRow(UUID id, String name, boolean isDefault, boolean active) {
    }

    record TransferEdgeRow(UUID fromDepartmentId, UUID toDepartmentId) {
    }

    /** One row of a department's configured visit flow, in step_order. staffingDepartmentId/Name
     * are informational only — see V39's migration comment. */
    record FlowStepRow(String stepType, UUID staffingDepartmentId, String staffingDepartmentName) {
    }
}
