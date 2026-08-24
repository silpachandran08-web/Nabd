package com.nabd.hms.clinical;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

final class ConditionModels {

    private ConditionModels() {
    }

    record ConditionRow(UUID id, UUID patientId, String condition, String status, LocalDate reviewDueDate,
                         UUID recordedBy, Instant recordedAt) {
    }

    /** One row of the clinic-wide "chronic review due" list — condition plus which patient it's for. */
    record DueConditionRow(UUID id, UUID patientId, String patientName, String condition, LocalDate reviewDueDate) {
    }
}
