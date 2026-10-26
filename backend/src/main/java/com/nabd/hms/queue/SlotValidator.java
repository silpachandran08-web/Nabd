package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.ClinicClock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import static com.nabd.hms.queue.QueueModels.WorkingHoursRow;

/**
 * The one place that decides whether a time may be booked, used by staff booking, reschedule and the waitlist
 * chosen slot. Same rules as ScheduleGrid/availability: inside a working-hours block, on that block's slot
 * grid (anchored to the block start, slot must end by the block end), not a holiday, not on leave, in the future.
 * Reads the database every call (ADR-04: no cached slots at booking).
 */
@Component
class SlotValidator {

    private final ScheduleRepository scheduleRepo;
    private final ClinicClock clock;
    private final WaitlistRepository waitlistRepo;
    private final QueueRepository queueRepo;

    SlotValidator(ScheduleRepository scheduleRepo, ClinicClock clock, WaitlistRepository waitlistRepo, QueueRepository queueRepo) {
        this.scheduleRepo = scheduleRepo;
        this.clock = clock;
        this.waitlistRepo = waitlistRepo;
        this.queueRepo = queueRepo;
    }

    /**
     * ACC-026: every booking path (desk book, Edge booking, reschedule, waitlist chosen slot) refuses a slot that is on a live
     * waitlist offer to someone else: 409 SLOT_ON_OFFER with the offer's expiry. Takes the doctor-day lock first, the same one
     * offers and walk-in tokens use, so the check and the insert that follows cannot interleave with a new offer.
     */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    void requireNoLiveOffer(UUID tenantId, UUID doctorId, Instant start, UUID exceptEntryId) {
        queueRepo.lockDoctorDay(tenantId, doctorId, start.atZone(clock.zone(tenantId)).toLocalDate());
        waitlistRepo.liveOfferExpiry(tenantId, doctorId, start, exceptEntryId).ifPresent(expiresAt -> {
            throw new ApiException(HttpStatus.CONFLICT, "slot-on-offer", "Slot is on offer",
                    "This slot is currently offered to a patient on the waitlist.",
                    java.util.Map.of("code", "SLOT_ON_OFFER", "expiresAt", expiresAt.toString()));
        });
    }

    /** Returns the slot length in minutes, or throws the Problem naming the first broken rule. */
    int validate(UUID tenantId, UUID doctorId, Instant start, Instant now) {
        ZoneId zone = clock.zone(tenantId);
        ZonedDateTime local = start.atZone(zone);
        LocalDate date = local.toLocalDate();
        if (scheduleRepo.isOnLeave(doctorId, date)) {
            throw refuse("doctor-on-leave", "DOCTOR_ON_LEAVE", "Doctor on leave", "The doctor is on leave on " + date + ".");
        }
        LocalTime time = local.toLocalTime();
        WorkingHoursRow block = scheduleRepo.findBlockCovering(doctorId, local.getDayOfWeek().getValue() % 7, time)
                .orElseThrow(() -> refuse("outside-hours", "OUTSIDE_HOURS", "Outside working hours",
                        "The doctor does not work at that time."));
        int offset = ScheduleGrid.minuteOf(time) - ScheduleGrid.minuteOf(block.startTime());
        boolean onGrid = time.getSecond() == 0 && time.getNano() == 0 && offset % block.slotMinutes() == 0;
        if (!onGrid) {
            throw refuse("off-grid", "OFF_GRID", "Not on the slot grid",
                    "Slots start every " + block.slotMinutes() + " minutes from " + block.startTime() + ".");
        }
        if (ScheduleGrid.minuteOf(time) + block.slotMinutes() > ScheduleGrid.minuteOf(block.endTime())) {
            throw refuse("outside-hours", "OUTSIDE_HOURS", "Outside working hours",
                    "A full slot does not fit before the session ends at " + block.endTime() + ".");
        }
        if (scheduleRepo.isClinicHoliday(tenantId, date)) {
            throw refuse("clinic-closed", "CLINIC_CLOSED", "Clinic closed", "The clinic is closed on " + date + ".");
        }
        if (!start.isAfter(now)) {
            throw refuse("in-past", "IN_PAST", "Time in the past", "The chosen time has already passed.");
        }
        return block.slotMinutes();
    }

    /** The exclusion constraint is the only integrity failure here that means "slot taken"; anything else stays a 500. */
    static ApiException slotTakenOrRethrow(org.springframework.dao.DataAccessException e) {
        String msg = String.valueOf(e.getMostSpecificCause().getMessage());
        // two concurrent overlapping inserts can deadlock inside the exclusion check; the loser is a slot-taken too
        boolean exclusionDeadlock = e instanceof org.springframework.dao.PessimisticLockingFailureException
                && msg.contains("exclusion constraint") && msg.contains("\"appointments\"");
        if (!msg.contains("ex_appointments_doctor_overlap") && !exclusionDeadlock) {
            throw e;
        }
        return new ApiException(HttpStatus.CONFLICT, "slot-taken", "Slot no longer available",
                "This doctor already has an appointment at that time.", java.util.Map.of("code", "SLOT_TAKEN"));
    }

    private static ApiException refuse(String slug, String code, String title, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, slug, title, detail, java.util.Map.of("code", code));
    }
}
