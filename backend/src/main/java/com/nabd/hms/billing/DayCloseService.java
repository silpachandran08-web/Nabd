package com.nabd.hms.billing;

import com.nabd.hms.billing.dto.DayCloseRequest;
import com.nabd.hms.billing.dto.DayCloseSummaryResponse;
import com.nabd.hms.billing.dto.DayReopenRequest;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** E15 Billing & Day Close: the day's billing summary, closing it against a cash count, reopening it. */
@Service
public class DayCloseService {

    // Variance allowed before a note is required (wireframe: ₹200 India, SAR 20 Saudi Arabia).
    private static final BigDecimal TOLERANCE_IN = new BigDecimal("200.00");
    private static final BigDecimal TOLERANCE_KSA = new BigDecimal("20.00");

    private final DayCloseRepository repo;
    private final TenantContext tenantContext;
    private final ClinicClock clock;
    private final AuditService auditService;

    DayCloseService(DayCloseRepository repo, TenantContext tenantContext, ClinicClock clock, AuditService auditService) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public DayCloseSummaryResponse summary(UUID tenantId, LocalDate date) {
        tenantContext.set(tenantId);
        ZoneId zone = clock.zone(tenantId);
        LocalDate today = LocalDate.now(zone);
        LocalDate day = date != null ? date : today;
        Instant start = day.atStartOfDay(zone).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(zone).toInstant();
        boolean ksa = "KSA".equals(repo.region(tenantId).orElse("IN"));
        DayCloseSummaryResponse.Close close = repo.findClose(tenantId, day).map(c -> new DayCloseSummaryResponse.Close(
                c.status(), c.expectedCash(), c.countedCash(), c.variance(), c.note(), c.closedByName(), c.closedAt(),
                c.reopenedByName(), c.reopenedAt(), c.reopenReason())).orElse(null);
        return new DayCloseSummaryResponse(day, day.equals(today), ksa ? "SAR" : "INR", ksa ? "VAT" : "GST",
                ksa ? TOLERANCE_KSA : TOLERANCE_IN, repo.totals(tenantId, start, end), repo.methods(tenantId, start, end),
                repo.outstanding(tenantId, start, end), repo.pendingCheckouts(tenantId, day), repo.unbilled(tenantId, day),
                repo.taxBands(tenantId, start, end), repo.splitPaymentBills(tenantId, start, end), close);
    }

    /** Closes the clinic's today. Blocked while any ended consultation has no bill. */
    @Transactional
    public DayCloseSummaryResponse close(UUID tenantId, UUID staffId, DayCloseRequest req) {
        tenantContext.set(tenantId);
        ZoneId zone = clock.zone(tenantId);
        LocalDate today = LocalDate.now(zone);
        if (repo.isClosed(tenantId, today)) {
            throw new ApiException(HttpStatus.CONFLICT, "day-already-closed", "Day already closed",
                    "Today is already closed. Unlock it first to change anything.");
        }
        List<DayCloseSummaryResponse.Unbilled> unbilled = repo.unbilled(tenantId, today);
        if (!unbilled.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "day-close-blocked", "Close blocked",
                    unbilled.size() + (unbilled.size() == 1 ? " completed consultation still has" : " completed consultations still have")
                            + " no bill. Bill each one before the day can close.");
        }
        Instant start = today.atStartOfDay(zone).toInstant();
        Instant end = today.plusDays(1).atStartOfDay(zone).toInstant();
        BigDecimal expected = repo.methods(tenantId, start, end).stream().filter(m -> "cash".equals(m.method()))
                .findFirst().map(DayCloseSummaryResponse.Method::amount).orElse(BigDecimal.ZERO);
        BigDecimal variance = req.countedCash().subtract(expected);
        BigDecimal tolerance = "KSA".equals(repo.region(tenantId).orElse("IN")) ? TOLERANCE_KSA : TOLERANCE_IN;
        String note = req.note() == null || req.note().isBlank() ? null : req.note().strip();
        if (variance.abs().compareTo(tolerance) > 0 && note == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "variance-note-required", "Note required",
                    "The cash count is off by more than " + tolerance.toPlainString() + ". Add a note explaining the difference.");
        }
        UUID id = repo.upsertClose(tenantId, today, expected, req.countedCash(), variance, note, staffId);
        audit(tenantId, staffId, "day_close.close", id,
                Map.of("date", today.toString(), "expectedCash", expected, "countedCash", req.countedCash(), "variance", variance));
        return summary(tenantId, today);
    }

    /** Unlocks a closed day (default: today) so billing can be corrected; the reason is audited. */
    @Transactional
    public DayCloseSummaryResponse reopen(UUID tenantId, UUID staffId, LocalDate date, DayReopenRequest req) {
        tenantContext.set(tenantId);
        LocalDate day = date != null ? date : clock.today(tenantId);
        DayCloseRepository.CloseRow row = repo.findClose(tenantId, day).filter(c -> "closed".equals(c.status()))
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "day-not-closed", "Day isn't closed",
                        "There's no closed day to unlock on " + day + "."));
        repo.reopen(tenantId, row.id(), staffId, req.reason().strip());
        audit(tenantId, staffId, "day_close.reopen", row.id(), Map.of("date", day.toString(), "reason", req.reason().strip()));
        return summary(tenantId, day);
    }

    /** Billing writes land on the clinic's today; once today is closed they'd change a reconciled day. */
    void requireTodayOpen(UUID tenantId) {
        if (repo.isClosed(tenantId, clock.today(tenantId))) {
            throw new ApiException(HttpStatus.CONFLICT, "day-closed", "Day is closed",
                    "Today's billing is closed. Ask the owner to unlock the day before billing or taking payments.");
        }
    }

    private void audit(UUID tenantId, UUID staffId, String action, UUID entityId, Object after) {
        String[] actor = repo.actor(tenantId, staffId).orElse(new String[]{"Unknown", "Unknown"});
        auditService.record(tenantId, "staff", staffId, actor[0], actor[1], null, action, "day_close", entityId, null, after);
    }
}
