package com.nabd.hms.reports.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Owner home "Today at a glance" (wireframe owner Overview) — every figure is for the clinic's own day. */
public record OverviewResponse(
        LocalDate date,
        Visits visits,
        Collections collections,
        Checkout checkout,
        Packages packages,
        List<PaymentSplit> paymentSplit,
        DayClose dayClose,
        List<ActiveStaff> activeStaff,
        StaffSummary staff
) {
    public record Visits(int total, int completed, int inFlow) {
    }

    /** pendingInvoices: today's invoices not yet fully paid. */
    public record Collections(BigDecimal collected, int invoices, int pendingInvoices) {
    }

    /** outstanding: every unpaid/partial balance, not just today's. */
    public record Checkout(int pending, BigDecimal outstanding) {
    }

    public record Packages(int soldToday, long sessionsOwed) {
    }

    public record PaymentSplit(String method, BigDecimal amount) {
    }

    /** What stands between the clinic and closing the day. There's no day-close workflow yet (E15). */
    public record DayClose(int unpaidInvoices, int pendingCheckouts) {
    }

    /** Staff with a live session opened today. inConsult: a doctor with a patient in consultation now. */
    public record ActiveStaff(UUID staffId, String name, String roleName, String departmentName, Instant signedInAt,
                              boolean inConsult) {
    }

    public record StaffSummary(int active, int suspended, int invited, int roles) {
    }
}
