package com.nabd.hms.department;

import java.util.UUID;

final class DepartmentModels {
    private DepartmentModels() {
    }

    record DepartmentRow(UUID id, String name, boolean requiresVitals, boolean isDefault, boolean active) {
    }

    record TransferEdgeRow(UUID fromDepartmentId, UUID toDepartmentId) {
    }
}
