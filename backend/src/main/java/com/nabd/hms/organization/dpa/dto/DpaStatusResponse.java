package com.nabd.hms.organization.dpa.dto;

import java.time.Instant;
import java.util.List;

/** blockers: what still keeps the clinic on demo data ("DPA_REQUIRED", "PLAN_REQUIRED"). reacceptDueBy is set when a newer DPA than the accepted one exists. */
public record DpaStatusResponse(String version, String marketCode, String html, Accepted accepted, String dataGate,
                                List<String> blockers, Instant reacceptDueBy) {

    public record Accepted(String version, Instant at, String by, String method, String acceptanceId) {
    }
}
