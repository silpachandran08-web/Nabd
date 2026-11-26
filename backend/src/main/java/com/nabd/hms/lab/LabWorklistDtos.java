package com.nabd.hms.lab;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** PHL-044: lab worklist response with status counts and filtered orders, STAT first by TAT deadline. */
public final class LabWorklistDtos {

    private LabWorklistDtos() {
    }

    @Schema(name = "LabWorklistOrder")
    public record Order(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID patientId,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String priority,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String source,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant orderedAt,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant dueAt,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int testCount) {
    }

    @Schema(name = "LabWorklistCounts")
    public record Counts(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) int ordered,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int collected,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int inProgress,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int resulted,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int validated) {
    }

    @Schema(name = "LabWorklist")
    public record Worklist(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Counts counts,
                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Order> items) {
    }
}
