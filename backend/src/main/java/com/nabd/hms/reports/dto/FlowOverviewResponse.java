package com.nabd.hms.reports.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * ADM-041 owner overview: time KPIs (what patients feel) for a range, each with the change against the previous range and a
 * target. Read from the daily rollups only. Aggregates and staff names; no patient identifiers.
 * previousFrom/previousTo: the same weekday last week for range 'today', the preceding 7 or 30 days otherwise.
 */
public record FlowOverviewResponse(String range, UUID facilityId, LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo,
                                   Instant refreshedAt, String currency, List<FlowTile> tiles, List<FlowHourWait> waitByHour,
                                   List<FlowEtaDay> etaAccuracy14d, FlowTimeSplit timeSplit, List<FlowPunctuality> punctuality) {

    /**
     * code: wait, door_to_doctor, consult, eta_accuracy, booking_response, no_show_rate, visits, revenue. unit: min, pct, count or
     * money. value and delta are null when nothing was measured. lowerIsBetter null = neither direction is better (consult time).
     * n: the observations behind the value. trend: one value per day, oldest first, null = nothing measured that day.
     */
    public record FlowTile(String code, BigDecimal value, String unit, BigDecimal delta, BigDecimal target, Boolean lowerIsBetter, long n,
                           List<BigDecimal> trend) {
    }

    /** Median wait (minutes) of visits that checked in during this local hour of the range's last day. */
    public record FlowHourWait(int hour, BigDecimal minutes) {
    }

    /** Share (0-100) of visits called within 10 minutes of the time shown to the patient; pct null = no predictions that day. */
    public record FlowEtaDay(LocalDate day, BigDecimal pct, int n) {
    }

    /** Median minutes per stage; null = not measured in the range. */
    public record FlowTimeSplit(BigDecimal checkinToVitals, BigDecimal waiting, BigDecimal consult, BigDecimal checkout) {
    }

    /** On time = first patient called within 5 minutes of the doctor's scheduled start. */
    public record FlowPunctuality(UUID doctorId, String doctorName, int sessions, BigDecimal onTimePct) {
    }
}
