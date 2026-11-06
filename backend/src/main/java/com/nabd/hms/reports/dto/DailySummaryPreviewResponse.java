package com.nabd.hms.reports.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * ADM-043: what today's message would say for this clinic. params are the four template slots (day, figures, attention,
 * link) exactly as they would be sent: counts and amounts only, never a patient name, phone or diagnosis. partial means
 * the rollup has not yet caught up with the day's source rows.
 */
public record DailySummaryPreviewResponse(LocalDate date, boolean partial, List<String> params) {
}
