package com.nabd.hms.billing.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * ADM-044: the reception desk's end-of-day summary for one clinic day of the caller's clinic (facility = tenant).
 * collectionsByMode and unbilled are the day close's own figures (DayCloseRepository), so the desk and the close agree.
 * patientName fields are PHI: never log them.
 */
public record DeskSummaryResponse(
        LocalDate date,
        boolean today,
        String currency,
        DeskVisits visits,
        List<DayCloseSummaryResponse.Method> collectionsByMode, // cash, card, upi, other — always all four, refunds netted
        List<DayCloseSummaryResponse.Unbilled> unbilled,         // sent to checkout with no bill: these block the close
        List<DeskNoShow> noShows
) {
    /** Same definition as the owner overview: every queue entry of the day, completed ones, and those still in the flow. */
    public record DeskVisits(int total, int completed, int inFlow) {
    }

    /** A booking of the day whose time has passed and nobody checked in for (late arrivals excluded). */
    public record DeskNoShow(UUID appointmentId, String patientName, String doctorName, Instant startTime) {
    }
}
