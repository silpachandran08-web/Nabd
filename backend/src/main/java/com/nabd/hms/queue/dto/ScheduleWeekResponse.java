package com.nabd.hms.queue.dto;

import java.time.LocalDate;
import java.util.List;

/** Week overview: per day, booked and free slot counts for each session label (Morning/Afternoon/Evening). */
public record ScheduleWeekResponse(LocalDate start, LocalDate today, List<Day> days) {

    public record Day(LocalDate date, String holiday, List<SessionCount> sessions) {
    }

    public record SessionCount(String label, int booked, int free) {
    }
}
