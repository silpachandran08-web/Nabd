package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.nabd.hms.queue.QueueModels.WorkingHoursRow;

/** ACC-011: availability by session (slot | token | hybrid) for one doctor and date. Read-only, no cache. */
@Service
public class SessionAvailabilityService {

    @Schema(name = "SessionAvailability")
    public record SessionView(String id, String mode, String label, List<Instant> slots, Integer tokensLeft,
                              Integer onlineLeft, String expectedRange) {
    }

    @Schema(name = "SessionAvailabilityResponse")
    public record SessionsResponse(List<SessionView> sessions) {
    }

    private final ScheduleRepository repo;
    private final QueueRepository queueRepo;
    private final TenantContext tenantContext;
    private final ClinicClock clock;

    SessionAvailabilityService(ScheduleRepository repo, QueueRepository queueRepo, TenantContext tenantContext, ClinicClock clock) {
        this.repo = repo;
        this.queueRepo = queueRepo;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    @Transactional
    public SessionsResponse sessions(UUID doctorId, LocalDate date) {
        UUID tenantId = tenantContext.current(); // bound at transaction start (PLT-019)
        if (repo.isClinicHoliday(tenantId, date) || repo.isOnLeave(doctorId, date)) {
            return new SessionsResponse(List.of());
        }
        ZoneId zone = clock.zone(tenantId);
        Instant now = clock.now();
        Set<Instant> booked = new HashSet<>(repo.bookedStartTimes(doctorId, date.atStartOfDay(zone).toInstant(),
                date.plusDays(1).atStartOfDay(zone).toInstant()));
        double avg = queueRepo.averageRecentVisitMinutes(tenantId, doctorId, 30).orElse(15.0);
        List<SessionView> out = new ArrayList<>();
        for (WorkingHoursRow wh : repo.findForDay(doctorId, date.getDayOfWeek().getValue() % 7)) {
            String mode = SessionModes.modeOn(wh, date);
            Instant start = date.atTime(wh.startTime()).atZone(zone).toInstant();
            Instant end = date.atTime(wh.endTime()).atZone(zone).toInstant();
            Integer tokensLeft = null, onlineLeft = null;
            if (wh.maxPatients() != null) {
                tokensLeft = Math.max(0, wh.maxPatients() - repo.countSessionOccupancy(tenantId, doctorId, date, wh.startTime(), wh.endTime(), zone));
            }
            if (wh.onlineCap() != null) {
                onlineLeft = Math.max(0, wh.onlineCap() - repo.countSessionBookings(tenantId, doctorId, date, wh.startTime(), wh.endTime(), zone));
            }
            List<Instant> slots = null;
            String range = null;
            if (!SessionModes.TOKEN.equals(mode)) {
                Set<Integer> reserved = repo.reservedMinutes(wh.id());
                slots = new ArrayList<>();
                for (Instant s = start; !s.plus(wh.slotMinutes(), ChronoUnit.MINUTES).isAfter(end); s = s.plus(wh.slotMinutes(), ChronoUnit.MINUTES)) {
                    if (s.isAfter(now) && !booked.contains(s) && !reserved.contains(ScheduleGrid.minuteOf(s.atZone(zone).toLocalTime()))) {
                        slots.add(s);
                    }
                }
            } else if (wh.maxPatients() != null) {
                // the next place a booking would get; a full session shows no range
                int next = repo.lowestFreeToken(tenantId, doctorId, wh.id(), start, wh.maxPatients()).orElse(0);
                range = next == 0 ? null : SessionModes.expectedRange(start, end, next, avg, zone);
            }
            out.add(new SessionView(wh.id() + "@" + date, mode, wh.label(), slots,
                    tokensLeft == null ? null : (onlineLeft == null ? tokensLeft : Math.min(tokensLeft, onlineLeft)),
                    onlineLeft, range));
        }
        return new SessionsResponse(out);
    }
}
