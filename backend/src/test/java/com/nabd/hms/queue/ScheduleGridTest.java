package com.nabd.hms.queue;

import com.nabd.hms.queue.dto.ScheduleDayResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The Schedule grid's layout rules, without a database. */
class ScheduleGridTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DAY = LocalDate.of(2030, 1, 7);
    private static final Instant BEFORE_DAY = DAY.minusDays(1).atStartOfDay(IST).toInstant();
    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), C = UUID.randomUUID(), D = UUID.randomUUID();

    private static int m(int h, int min) {
        return h * 60 + min;
    }

    private static ScheduleGrid.BookingInput appt(UUID doctor, int start, int end) {
        return new ScheduleGrid.BookingInput(doctor, start, end, new ScheduleDayResponse.Booking(UUID.randomUUID(), null,
                UUID.randomUUID(), "P", "appointment", ScheduleGrid.hhmm(start), null, "scheduled"));
    }

    private static ScheduleGrid.BookingInput walkIn(UUID doctor, int at) {
        return new ScheduleGrid.BookingInput(doctor, at, at, new ScheduleDayResponse.Booking(null, UUID.randomUUID(),
                UUID.randomUUID(), "W", "walk_in", ScheduleGrid.hhmm(at), 7, "waiting"));
    }

    private static List<ScheduleGrid.DoctorInput> doctors() {
        return List.of(
                new ScheduleGrid.DoctorInput(A, "A", null, false, null, List.of(
                        new ScheduleGrid.Block(m(9, 0), m(11, 0), 30), new ScheduleGrid.Block(m(16, 0), m(17, 0), 30))),
                new ScheduleGrid.DoctorInput(B, "B", null, false, null, List.of(new ScheduleGrid.Block(m(9, 0), m(10, 0), 30))),
                new ScheduleGrid.DoctorInput(C, "C", null, true, "Conference", List.of(new ScheduleGrid.Block(m(9, 0), m(12, 0), 30))),
                new ScheduleGrid.DoctorInput(D, "D", null, false, null, List.of()));
    }

    private static String kind(ScheduleDayResponse.Session s, String time, int col) {
        return s.rows().stream().filter(r -> r.time().equals(time)).findFirst().orElseThrow().cells().get(col).kind();
    }

    @Test
    void sessionsAreMergedWorkingHoursAndCellsFollowEachDoctorsDay() {
        List<ScheduleDayResponse.Session> s = ScheduleGrid.build(DAY, IST, BEFORE_DAY, false, doctors(),
                List.of(appt(A, m(9, 0), m(9, 30)), walkIn(B, m(9, 40))));

        assertThat(s).extracting(ScheduleDayResponse.Session::label).containsExactly("Morning", "Evening");
        assertThat(s.get(0).start()).isEqualTo("09:00");
        assertThat(s.get(0).end()).isEqualTo("12:00");
        assertThat(s.get(0).rows()).extracting(ScheduleDayResponse.Row::time)
                .containsExactly("09:00", "09:30", "10:00", "10:30", "11:00", "11:30");

        ScheduleDayResponse.Session am = s.get(0);
        assertThat(kind(am, "09:00", 0)).isEqualTo("booked");
        assertThat(kind(am, "09:30", 0)).isEqualTo("available");
        assertThat(kind(am, "11:00", 0)).isEqualTo("break");   // A stops at 11:00, session runs to 12:00
        assertThat(kind(am, "09:30", 1)).isEqualTo("booked");  // B's 09:40 walk-in lands in the 09:30 row
        assertThat(kind(am, "10:00", 1)).isEqualTo("break");
        assertThat(kind(am, "09:00", 2)).isEqualTo("leave");
        assertThat(kind(am, "09:00", 3)).isEqualTo("off");

        ScheduleDayResponse.Cell free = am.rows().get(1).cells().get(0);
        assertThat(free.slotStart()).isEqualTo(DAY.atTime(9, 30).atZone(IST).toInstant()); // clinic wall clock → instant
    }

    @Test
    void pastHolidayOccupiedAndOutOfHoursBookings() {
        Instant afterDay = DAY.plusDays(1).atStartOfDay(IST).toInstant();
        assertThat(kind(ScheduleGrid.build(DAY, IST, afterDay, false, doctors(), List.of()).get(0), "09:30", 0)).isEqualTo("past");
        assertThat(kind(ScheduleGrid.build(DAY, IST, BEFORE_DAY, true, doctors(), List.of()).get(0), "09:30", 0)).isEqualTo("closed");

        // a 60-minute appointment holds the next row too
        assertThat(kind(ScheduleGrid.build(DAY, IST, BEFORE_DAY, false, doctors(), List.of(appt(A, m(9, 0), m(10, 0)))).get(0),
                "09:30", 0)).isEqualTo("occupied");

        // a walk-in at 13:10, outside everyone's hours, still gets its own Afternoon band
        List<ScheduleDayResponse.Session> s = ScheduleGrid.build(DAY, IST, BEFORE_DAY, false, doctors(), List.of(walkIn(A, m(13, 10))));
        ScheduleDayResponse.Session pm = s.stream().filter(x -> x.label().equals("Afternoon")).findFirst().orElseThrow();
        assertThat(pm.rows()).extracting(ScheduleDayResponse.Row::time).containsExactly("13:00");
        assertThat(kind(pm, "13:00", 0)).isEqualTo("booked");
    }

    @Test
    void rowsStepByTheShortestSlotAndLongerSlotsShowTheirMiddleAsOccupied() {
        List<ScheduleGrid.DoctorInput> mixed = List.of(
                new ScheduleGrid.DoctorInput(A, "A", null, false, null, List.of(new ScheduleGrid.Block(m(9, 0), m(10, 0), 30))),
                new ScheduleGrid.DoctorInput(B, "B", null, false, null, List.of(new ScheduleGrid.Block(m(9, 0), m(10, 0), 15))));
        ScheduleDayResponse.Session am = ScheduleGrid.build(DAY, IST, BEFORE_DAY, false, mixed, List.of()).get(0);
        assertThat(am.stepMinutes()).isEqualTo(15);
        assertThat(kind(am, "09:15", 0)).isEqualTo("occupied");
        assertThat(kind(am, "09:15", 1)).isEqualTo("available");
    }

    @Test
    void anEarlyWalkInGetsItsOwnRowWithoutShiftingSlotRows() {
        ScheduleDayResponse.Session am = ScheduleGrid.build(DAY, IST, BEFORE_DAY, false, doctors(),
                List.of(walkIn(B, m(8, 47)))).get(0);
        // 08:30–09:00 joins straight onto the 09:00 working rows, which stay on their slot boundaries
        assertThat(am.start()).isEqualTo("08:30");
        assertThat(am.rows()).extracting(ScheduleDayResponse.Row::time).startsWith("08:30", "09:00", "09:30");
        assertThat(kind(am, "08:30", 1)).isEqualTo("booked");
        assertThat(kind(am, "09:00", 1)).isEqualTo("available");
    }

    @Test
    void aBookingJustAfterHoursJoinsTheSessionAsOneRow() {
        ScheduleDayResponse.Session am = ScheduleGrid.build(DAY, IST, BEFORE_DAY, false, doctors(),
                List.of(walkIn(A, m(12, 10)))).get(0);
        assertThat(am.end()).isEqualTo("12:30");
        assertThat(am.rows()).extracting(ScheduleDayResponse.Row::time).endsWith("11:30", "12:00");
        assertThat(kind(am, "12:00", 0)).isEqualTo("booked");
    }
}
