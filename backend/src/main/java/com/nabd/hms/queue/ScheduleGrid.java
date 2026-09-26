package com.nabd.hms.queue;

import com.nabd.hms.queue.dto.ScheduleDayResponse;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Pure layout for the Schedule day grid — no database, so the rules are unit-testable
 * (ScheduleGridTest). Works in minutes-of-day on the clinic's wall clock.
 *
 * <p>Sessions are the merged union of every shown doctor's working-hours blocks for that weekday,
 * plus a row for any booking outside them, so a walk-in or appointment outside anyone's hours still
 * shows up instead of silently disappearing.
 */
final class ScheduleGrid {

    static final int DEFAULT_STEP = 30;

    record Block(int start, int end, int slotMinutes) {
    }

    record DoctorInput(UUID id, String name, String department, boolean onLeave, String leaveReason, List<Block> blocks) {
    }

    /** end == start for walk-ins: they sit in the queue, they don't hold a slot. */
    record BookingInput(UUID doctorId, int start, int end, ScheduleDayResponse.Booking booking) {
    }

    private ScheduleGrid() {
    }

    static List<ScheduleDayResponse.Session> build(LocalDate date, ZoneId zone, Instant now, boolean holiday,
                                                   List<DoctorInput> doctors, List<BookingInput> bookings) {
        // 1. Working-hours blocks, merged where they touch or overlap: rows at the band's shortest
        //    slot length, anchored to its start so every slot boundary gets a row.
        List<int[]> blocks = new ArrayList<>(); // [start, end, slot]
        doctors.forEach(d -> d.blocks().forEach(b -> blocks.add(new int[]{b.start(), b.end(), b.slotMinutes()})));
        blocks.sort(Comparator.comparingInt(b -> b[0]));
        List<int[]> bands = new ArrayList<>(); // [start, end, step]
        for (int[] b : blocks) {
            int[] last = bands.isEmpty() ? null : bands.get(bands.size() - 1);
            if (last != null && b[0] <= last[1]) {
                last[1] = Math.max(last[1], b[1]);
                last[2] = Math.min(last[2], b[2]);
            } else {
                bands.add(new int[]{b[0], b[1], b[2]});
            }
        }
        List<int[]> rows = new ArrayList<>(); // [time, step]
        for (int[] band : bands) {
            for (int t = band[0]; t < band[1]; t += band[2]) {
                rows.add(new int[]{t, Math.min(band[2], band[1] - t)});
            }
        }

        // 2. A booking outside every band gets a row of its own at its half-hour, trimmed so it never
        //    overlaps a working row — so it can't silently disappear from the grid.
        for (BookingInput b : bookings) {
            if (bands.stream().anyMatch(band -> b.start() >= band[0] && b.start() < band[1])
                    || rows.stream().anyMatch(r -> b.start() >= r[0] && b.start() < r[0] + r[1])) {
                continue;
            }
            int t = b.start() - b.start() % DEFAULT_STEP;
            int end = t + DEFAULT_STEP;
            for (int[] r : rows) {
                if (r[0] > b.start() && r[0] < end) {
                    end = r[0];
                }
                if (r[0] + r[1] > t && r[0] + r[1] <= b.start()) {
                    t = r[0] + r[1];
                }
            }
            rows.add(new int[]{t, end - t});
        }
        rows.sort(Comparator.comparingInt(r -> r[0]));

        // 3. Contiguous rows form a session.
        List<ScheduleDayResponse.Session> sessions = new ArrayList<>();
        List<int[]> current = new ArrayList<>();
        for (int[] r : rows) {
            if (!current.isEmpty()) {
                int[] prev = current.get(current.size() - 1);
                if (r[0] != prev[0] + prev[1]) {
                    sessions.add(session(current, date, zone, now, holiday, doctors, bookings));
                    current = new ArrayList<>();
                }
            }
            current.add(r);
        }
        if (!current.isEmpty()) {
            sessions.add(session(current, date, zone, now, holiday, doctors, bookings));
        }
        return sessions;
    }

    private static ScheduleDayResponse.Session session(List<int[]> rows, LocalDate date, ZoneId zone, Instant now,
                                                       boolean holiday, List<DoctorInput> doctors, List<BookingInput> bookings) {
        List<ScheduleDayResponse.Row> out = new ArrayList<>();
        for (int[] r : rows) {
            List<ScheduleDayResponse.Cell> cells = new ArrayList<>();
            for (DoctorInput d : doctors) {
                cells.add(cell(d, r[0], r[1], date, zone, now, holiday, bookings));
            }
            out.add(new ScheduleDayResponse.Row(hhmm(r[0]), cells));
        }
        int start = rows.get(0)[0];
        int[] last = rows.get(rows.size() - 1);
        int step = rows.stream().mapToInt(r -> r[1]).min().orElse(DEFAULT_STEP);
        return new ScheduleDayResponse.Session(label(start), hhmm(start), hhmm(last[0] + last[1]), step, out);
    }

    private static ScheduleDayResponse.Cell cell(DoctorInput d, int t, int step, LocalDate date, ZoneId zone,
                                                 Instant now, boolean holiday, List<BookingInput> bookings) {
        List<ScheduleDayResponse.Booking> here = bookings.stream()
                .filter(b -> b.doctorId().equals(d.id()) && b.start() >= t && b.start() < t + step)
                .map(BookingInput::booking).toList();
        if (!here.isEmpty()) {
            return new ScheduleDayResponse.Cell(d.id(), "booked", null, here);
        }
        if (d.onLeave()) {
            return empty(d, "leave");
        }
        if (d.blocks().isEmpty()) {
            return empty(d, "off");
        }
        Block block = d.blocks().stream().filter(b -> b.start() <= t && t < b.end()).findFirst().orElse(null);
        if (block == null) {
            return empty(d, "break");
        }
        boolean insideAppointment = bookings.stream().anyMatch(b ->
                b.doctorId().equals(d.id()) && b.end() > b.start() && b.start() < t + step && b.end() > t);
        boolean onSlotBoundary = (t - block.start()) % block.slotMinutes() == 0 && t + block.slotMinutes() <= block.end();
        if (insideAppointment || !onSlotBoundary) {
            return empty(d, "occupied");
        }
        if (holiday) {
            return empty(d, "closed");
        }
        Instant slotStart = date.atTime(LocalTime.of(t / 60, t % 60)).atZone(zone).toInstant();
        if (!slotStart.isAfter(now)) {
            return empty(d, "past");
        }
        return new ScheduleDayResponse.Cell(d.id(), "available", slotStart, List.of());
    }

    private static ScheduleDayResponse.Cell empty(DoctorInput d, String kind) {
        return new ScheduleDayResponse.Cell(d.id(), kind, null, List.of());
    }

    static String label(int startMinute) {
        return startMinute < 12 * 60 ? "Morning" : startMinute < 16 * 60 ? "Afternoon" : "Evening";
    }

    static String hhmm(int minute) {
        return minute >= 1440 ? "24:00" : String.format("%02d:%02d", minute / 60, minute % 60);
    }

    static int minuteOf(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }
}
