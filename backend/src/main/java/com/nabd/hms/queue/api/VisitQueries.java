package com.nabd.hms.queue.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PLT-035: what billing reads about visits (checkout context, the follow-up window, day close and the desk summary), so no billing
 * SQL touches queue_entries or appointments. Runs in the caller's transaction; a visit is one queue_entries row (one leg).
 */
public interface VisitQueries {

    record Visit(UUID patientId, UUID doctorId, UUID departmentId, String status, boolean hasAppointment, boolean urgent) {
    }

    record VisitDay(UUID visitId, LocalDate date) {
    }

    record AwaitingCheckout(UUID visitId, int tokenNumber, String patientName, String doctorName, Instant since) {
    }

    record DayCounts(int total, int completed, int inFlow) {
    }

    record NoShow(UUID appointmentId, String patientName, UUID doctorId, Instant startTime) {
    }

    Optional<Visit> visit(UUID tenantId, UUID visitId);

    /** FIN-036: the clinic date of a visit (a policy is checked against the day of the visit, not today); empty when unknown. */
    Optional<LocalDate> visitDate(UUID tenantId, UUID visitId);

    /** FIN-015: the patient's completed visits with this doctor, newest clinic date first. */
    List<VisitDay> completedVisits(UUID tenantId, UUID patientId, UUID doctorId);

    /** Visits of the clinic day waiting at a billing stop (checkout_pending or billing_pending). */
    int atBillingStop(UUID tenantId, LocalDate day);

    /** Visits of the clinic day at checkout_pending, oldest move first. */
    List<AwaitingCheckout> awaitingCheckout(UUID tenantId, LocalDate day);

    /** ADM-044: the day's visits (total, completed, still in the flow). */
    DayCounts dayCounts(UUID tenantId, LocalDate day);

    /** ADM-044: non-cancelled bookings in [from, to) whose start passed before {@code now} and that were never checked in. */
    List<NoShow> noShows(UUID tenantId, Instant from, Instant to, Instant now);
}
