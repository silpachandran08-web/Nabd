package com.nabd.hms.reports.dto;

import java.util.List;
import java.util.UUID;

/** NB-235: rule is echoed back verbatim so the surface always states exactly what flagged each
 * entry — transparent and auditable by construction, not an opaque model. */
public record NoShowRiskResponse(String rule, List<Entry> entries) {

    public record Entry(UUID patientId, String patientName, long priorNoShowCount) {
    }
}
