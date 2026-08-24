package com.nabd.hms.reports.dto;

import java.util.List;
import java.util.UUID;

/** NB-236: accessNote states on the surface that this is Owner-only — no per-patient log either. */
public record DoctorPunctualityResponse(String accessNote, List<Entry> entries) {

    public record Entry(UUID doctorId, String doctorName, long delayCount, double avgDelayMinutes, long sameDayRepeatDays) {
    }
}
