package com.nabd.hms.queue.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Clinic Operations → Schedule (DESIGN.md "Schedule & Appointments"): one day as a grid of
 * sessions × rows × doctors, computed server-side in the clinic's own timezone. Times are the
 * clinic's wall clock ("HH:mm"); slotStart is the exact instant a booking at that cell would use.
 */
public record ScheduleDayResponse(
        LocalDate date,
        boolean today,
        String timezone,
        String holiday,                 // clinic holiday name, or null
        List<Doctor> doctors,
        List<Session> sessions,
        Counts counts
) {
    public record Doctor(UUID id, String name, String department, boolean onLeave, String leaveReason, boolean worksToday) {
    }

    /** A contiguous band of working time (e.g. Morning 09:00–12:00); rows step by its smallest slot length. */
    public record Session(String label, String start, String end, int stepMinutes, List<Row> rows) {
    }

    public record Row(String time, List<Cell> cells) {
    }

    /**
     * kind: booked (bookings non-empty) · available (bookable, slotStart set) · past (working time
     * already gone) · occupied (inside a longer appointment or between a doctor's slot boundaries)
     * · break (outside this doctor's hours but inside the session) · off (doctor doesn't work this
     * weekday) · leave (doctor on leave).
     */
    public record Cell(UUID doctorId, String kind, Instant slotStart, List<Booking> bookings) {
    }

    /** visitType: appointment · follow_up · walk_in · transfer. status: the queue status once the
     * patient has arrived (token set), otherwise the appointment's own status. */
    public record Booking(UUID appointmentId, UUID queueEntryId, UUID patientId, String patientName, String visitType,
                          String time, Integer token, String status) {
    }

    public record Counts(int appointments, int walkIns, int freeSlots) {
    }
}
