package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.nabd.hms.queue.QueueModels.WorkingHoursRow;

/** ACC-010: the nearest free slot of a doctor after a given instant, by the same rules as the availability read (holds and bookings both block). */
@Component
class SlotAlternatives {

    private static final int HORIZON_DAYS = 14;

    private final ScheduleRepository scheduleRepo;
    private final ClinicClock clock;

    SlotAlternatives(ScheduleRepository scheduleRepo, ClinicClock clock) {
        this.scheduleRepo = scheduleRepo;
        this.clock = clock;
    }

    /** Needs the caller's transaction (tenant bound). shortcut: ignores the live delay of the doctor, upgrade with the ETA engine (ACC-016/017). */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Instant> nearest(UUID tenantId, UUID doctorId, Instant after) {
        ZoneId zone = clock.zone(tenantId);
        Instant now = clock.now();
        Instant from = after.isAfter(now) ? after : now;
        LocalDate first = from.atZone(zone).toLocalDate();
        for (LocalDate day = first; day.isBefore(first.plusDays(HORIZON_DAYS)); day = day.plusDays(1)) {
            if (scheduleRepo.isClinicHoliday(tenantId, day) || scheduleRepo.isOnLeave(doctorId, day)) continue;
            Set<Instant> booked = new HashSet<>(scheduleRepo.bookedStartTimes(doctorId, day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant()));
            var shortDay = scheduleRepo.shortWindows(tenantId, day); // ADM-028
            for (WorkingHoursRow wh : scheduleRepo.findForDay(doctorId, day.getDayOfWeek().getValue() % 7).stream()
                    .sorted(Comparator.comparing(WorkingHoursRow::startTime)).toList()) {
                if (wh.maxPatients() != null && scheduleRepo.countSessionOccupancy(tenantId, doctorId, day, wh.startTime(), wh.endTime(), zone) >= wh.maxPatients()) continue;
                Instant end = day.atTime(wh.endTime()).atZone(zone).toInstant();
                for (Instant s = day.atTime(wh.startTime()).atZone(zone).toInstant(); !s.plus(wh.slotMinutes(), ChronoUnit.MINUTES).isAfter(end);
                     s = s.plus(wh.slotMinutes(), ChronoUnit.MINUTES)) {
                    var local = s.atZone(zone).toLocalTime();
                    if (s.isAfter(from) && !booked.contains(s) && ScheduleRepository.inWindows(shortDay, local, local.plusMinutes(wh.slotMinutes()))) return Optional.of(s);
                }
            }
        }
        return Optional.empty();
    }
}
