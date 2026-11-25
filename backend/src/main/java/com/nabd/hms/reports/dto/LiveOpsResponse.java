package com.nabd.hms.reports.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** ADM-042: GET /v1/reports/live. Staff names, ids, counts and minutes only: never a patient. lab is the typed slot of the lab turnaround station (always null until lab orders exist). */
public record LiveOpsResponse(Instant asOf, OpsThresholds thresholds, List<LiveDoctor> doctors, LiveStation pharmacy, LiveStation requests,
                              LiveStation lab, List<LiveAlert> alerts) {

    /** status: in, paused, out or not_started. longestWaitMin is null when nobody waits. */
    public record LiveDoctor(UUID id, String name, String status, int waiting, int booked, int delayMin, Integer longestWaitMin, int told) {
    }

    /** A station's open work and its longest wait in minutes (null when nothing is open). */
    public record LiveStation(int open, Integer longestWaitMin) {
    }

    /**
     * code: doctor_late, pharmacy_wait or requests_waiting (lab_tat_breach is reserved). severity: warning, or critical at twice the threshold.
     * actions: broadcast_delay, open_pharmacy, open_requests. doctorId, delayId, waiting and told are set for doctor_late; requestIds for requests_waiting.
     */
    public record LiveAlert(String code, String severity, String summary, List<String> actions, Integer minutes, UUID doctorId, UUID delayId,
                            Integer waiting, Integer told, List<UUID> requestIds) {
    }
}
