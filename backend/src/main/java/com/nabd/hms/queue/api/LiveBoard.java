package com.nabd.hms.queue.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * ADM-042: what the owner's live operations board reads about today's doctors and the held booking requests, so no reporting SQL
 * touches the scheduling tables. Runs in the caller's tenant transaction. Ids, counts, states and times only: no patient data.
 */
public interface LiveBoard {

    /**
     * One doctor with something happening today (a session, a visit, a booking still to arrive or an announced delay).
     * @param status       in, paused or out (the latest session of the clinic day), or not_started
     * @param waiting      visits checked in and not yet called
     * @param waitingSince when the longest-waiting of them checked in; null when nobody waits
     * @param booked       today's bookings that have not arrived yet
     * @param delayMin     minutes late: the announced delay still in force, or the time since today's first session start while the
     *                     doctor has neither opened a session nor called a patient (0 on leave or outside working hours)
     * @param delayId      the announced delay still in force; null when none
     * @param told         patients already sent a delay or pause message today
     */
    record Doctor(UUID id, String status, int waiting, Instant waitingSince, int booked, int delayMin, UUID delayId, int told) {
    }

    /** A booking request still waiting for a decision. */
    record OpenRequest(UUID id, Instant since) {
    }

    List<Doctor> doctors(UUID tenantId, Instant now);

    /** Undecided booking requests, longest waiting first. */
    List<OpenRequest> openRequests(UUID tenantId, int limit);
}
