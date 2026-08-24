package com.nabd.hms.reports.dto;

/** NB-234: aggregate-only — no individual patient list is ever exposed via this response, per the
 * ticket's own acceptance bar. */
public record RetentionResponse(int totalPatients, int repeatPatients, double repeatRatePercent,
                                 double avgVisitsPerPatient) {
}
