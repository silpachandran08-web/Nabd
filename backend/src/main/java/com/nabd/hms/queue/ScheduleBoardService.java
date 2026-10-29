package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.queue.dto.ScheduleDayResponse;
import com.nabd.hms.queue.dto.ScheduleWeekResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Clinic Operations → Schedule: the day grid and week overview, all on the clinic's own clock. */
@Service
public class ScheduleBoardService {

    private final ScheduleBoardRepository repo;
    private final TenantContext tenantContext;
    private final ClinicClock clock;

    ScheduleBoardService(ScheduleBoardRepository repo, TenantContext tenantContext, ClinicClock clock) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ScheduleDayResponse day(UUID tenantId, LocalDate date) {
        tenantContext.set(tenantId);
        ZoneId zone = clock.zone(tenantId);
        LocalDate day = date != null ? date : clock.clinicDate(tenantId);
        return build(tenantId, day, zone, clock.clinicDate(tenantId), clock.now());
    }

    @Transactional(readOnly = true)
    public ScheduleWeekResponse week(UUID tenantId, LocalDate start) {
        tenantContext.set(tenantId);
        ZoneId zone = clock.zone(tenantId);
        LocalDate today = clock.clinicDate(tenantId);
        LocalDate first = start != null ? start : today;
        Instant now = clock.now();
        List<ScheduleWeekResponse.Day> days = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            ScheduleDayResponse d = build(tenantId, first.plusDays(i), zone, today, now);
            Map<String, int[]> byLabel = new LinkedHashMap<>(); // label -> [booked, free]
            for (ScheduleDayResponse.Session s : d.sessions()) {
                int[] c = byLabel.computeIfAbsent(s.label(), k -> new int[2]);
                for (ScheduleDayResponse.Row row : s.rows()) {
                    for (ScheduleDayResponse.Cell cell : row.cells()) {
                        c[0] += cell.bookings().size();
                        c[1] += "available".equals(cell.kind()) ? 1 : 0;
                    }
                }
            }
            days.add(new ScheduleWeekResponse.Day(d.date(), d.holiday(), byLabel.entrySet().stream()
                    .map(e -> new ScheduleWeekResponse.SessionCount(e.getKey(), e.getValue()[0], e.getValue()[1])).toList()));
        }
        return new ScheduleWeekResponse(first, today, days);
    }

    private ScheduleDayResponse build(UUID tenantId, LocalDate day, ZoneId zone, LocalDate today, Instant now) {
        Instant dayStart = clock.dayStart(tenantId, day); // ACC-005: a clinic day runs from the day start to the next one
        Instant dayEnd = clock.dayStart(tenantId, day.plusDays(1));
        String holiday = repo.holidayName(tenantId, day).orElse(null);
        List<ScheduleBoardRepository.AppointmentRow> appts = repo.appointmentsBetween(tenantId, dayStart, dayEnd);
        List<ScheduleBoardRepository.WalkInRow> walkIns = repo.walkInsOn(tenantId, day);

        // Columns: the clinic's doctors, plus anyone a booking points at who isn't one.
        List<ScheduleBoardRepository.DoctorRow> doctorRows = new ArrayList<>(repo.doctors(tenantId));
        Set<UUID> known = doctorRows.stream().map(ScheduleBoardRepository.DoctorRow::id).collect(Collectors.toCollection(HashSet::new));
        List<UUID> extra = new ArrayList<>();
        appts.forEach(a -> { if (known.add(a.doctorId())) extra.add(a.doctorId()); });
        walkIns.forEach(w -> { if (w.doctorId() != null && known.add(w.doctorId())) extra.add(w.doctorId()); });
        doctorRows.addAll(repo.staffByIds(tenantId, extra));

        Map<UUID, List<ScheduleGrid.Block>> blocks = repo.blocksFor(tenantId, day.getDayOfWeek().getValue() % 7).stream()
                .collect(Collectors.groupingBy(ScheduleBoardRepository.BlockRow::doctorId, Collectors.mapping(b ->
                        new ScheduleGrid.Block(b.startMinute(), b.endMinute(), b.slotMinutes()),
                        Collectors.toList())));
        Map<UUID, String> leave = new LinkedHashMap<>();
        Map<UUID, List<int[]>> partial = new HashMap<>(); // ACC-005: part-day leave windows in minutes from the clinic date's midnight
        int dayStartSecond = clock.dayStartMinute(tenantId) * 60;
        repo.leaveOn(tenantId, day).forEach(l -> {
            if (l.start() == null) {
                leave.putIfAbsent(l.doctorId(), l.reason() == null ? "" : l.reason());
            } else {
                int[] w = QueueModels.leaveWindow(l.start(), l.end(), dayStartSecond);
                partial.computeIfAbsent(l.doctorId(), k -> new ArrayList<>()).add(new int[]{w[0] / 60, w[1] / 60});
            }
        });

        List<ScheduleGrid.DoctorInput> doctors = doctorRows.stream().map(d -> new ScheduleGrid.DoctorInput(
                d.id(), d.name(), d.department(), leave.containsKey(d.id()), blankToNull(leave.get(d.id())),
                blocks.getOrDefault(d.id(), List.of()), partial.getOrDefault(d.id(), List.of()))).toList();

        List<ScheduleGrid.BookingInput> bookings = new ArrayList<>();
        for (ScheduleBoardRepository.AppointmentRow a : appts) {
            int start = clock.secondOfClinicDay(tenantId, day, a.start()) / 60; // ACC-005: 00:30 after the evening session is minute 1470
            int end = clock.secondOfClinicDay(tenantId, day, a.end()) / 60;
            bookings.add(new ScheduleGrid.BookingInput(a.doctorId(), start, Math.max(end, start + 1), new ScheduleDayResponse.Booking(
                    a.id(), a.queueEntryId(), a.patientId(), a.patientName(), a.followUp() ? "follow_up" : "appointment",
                    ScheduleGrid.hhmm(start), a.token(), a.queueStatus() != null ? a.queueStatus() : a.status(), a.actedByRelation())));
        }
        for (ScheduleBoardRepository.WalkInRow w : walkIns) {
            if (w.doctorId() == null) {
                continue; // no doctor column to put it in; it still shows on the Live Queue
            }
            int start = clock.secondOfClinicDay(tenantId, day, w.createdAt()) / 60;
            bookings.add(new ScheduleGrid.BookingInput(w.doctorId(), start, start, new ScheduleDayResponse.Booking(
                    null, w.queueEntryId(), w.patientId(), w.patientName(),
                    "internal_transfer".equals(w.source()) ? "transfer" : "walk_in", ScheduleGrid.hhmm(start), w.token(), w.status(), w.actedByRelation())));
        }

        List<ScheduleDayResponse.Session> sessions = ScheduleGrid.build(day, zone, now, holiday != null, doctors, bookings);
        int free = (int) sessions.stream().flatMap(s -> s.rows().stream()).flatMap(r -> r.cells().stream())
                .filter(c -> "available".equals(c.kind())).count();
        List<ScheduleDayResponse.Doctor> doctorDtos = doctors.stream().map(d -> new ScheduleDayResponse.Doctor(
                d.id(), d.name(), d.department(), d.onLeave(), d.leaveReason(), !d.blocks().isEmpty())).toList();
        return new ScheduleDayResponse(day, day.equals(today), zone.getId(), holiday, doctorDtos, sessions,
                new ScheduleDayResponse.Counts(appts.size(), walkIns.size(), free));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
