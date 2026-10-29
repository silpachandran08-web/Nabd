package com.nabd.hms.queue;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** ACC-007: owner report tiles (no-show % per doctor per week, top 5 cancel reasons). */
@RestController
class BookingReasonsReportController {

    private final BookingReasonsReportService service;

    BookingReasonsReportController(BookingReasonsReportService service) {
        this.service = service;
    }

    @GetMapping("/v1/reports/booking-reasons")
    @PreAuthorize("hasAuthority('reports:view')")
    BookingReasonsReportService.Report report(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.report(from, to);
    }
}
