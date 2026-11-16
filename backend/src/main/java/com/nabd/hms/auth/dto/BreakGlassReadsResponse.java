package com.nabd.hms.auth.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** PLT-013: the records a break-glass holder opened during the grant, oldest first (from phi_read_log). patientRef is the MRN. */
public record BreakGlassReadsResponse(List<Item> items) {
    public record Item(UUID patientId, String patientRef, String patientName, String resourceType, Instant readAt) {
    }
}
