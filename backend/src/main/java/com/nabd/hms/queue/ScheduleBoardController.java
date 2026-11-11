package com.nabd.hms.queue;

import com.nabd.hms.common.CurrentTenant;
import com.nabd.hms.queue.dto.ScheduleDayResponse;
import com.nabd.hms.queue.dto.ScheduleWeekResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** Clinic Operations → Schedule. Read-only: booking goes through AppointmentController as before. */
@RestController
@RequestMapping("/v1/schedule")
public class ScheduleBoardController {

    private final ScheduleBoardService service;

    ScheduleBoardController(ScheduleBoardService service) {
        this.service = service;
    }

    /** date defaults to the clinic's today. */
    @GetMapping("/day")
    @PreAuthorize("hasAuthority('queue:view')")
    public ScheduleDayResponse day(@CurrentTenant UUID tenantId,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.day(tenantId, date);
    }

    /** Seven days from start (default: the clinic's today). */
    @GetMapping("/week")
    @PreAuthorize("hasAuthority('queue:view')")
    public ScheduleWeekResponse week(@CurrentTenant UUID tenantId,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start) {
        return service.week(tenantId, start);
    }
}
