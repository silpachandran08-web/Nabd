package com.nabd.hms.billing;

import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.billing.dto.DayCloseHistoryResponse;
import com.nabd.hms.billing.dto.DayCloseRequest;
import com.nabd.hms.billing.dto.DayCloseSummaryResponse;
import com.nabd.hms.billing.dto.DayReopenRequest;
import com.nabd.hms.billing.dto.DeskSummaryResponse;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.organization.api.ClinicPolicies;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** E15 Billing & Day Close: the day's billing summary, closing it against a cash count, reopening it.
 * FIN-005: close/reopen take any past date, a reopened day closes again (history in day_close_events + audit_log),
 * and close, reopen and every billing write share one per-tenant-day advisory lock. */
@Service
public class DayCloseService {

    static final String CLOSED_EVENT = "billing.day.closed.v1";
    static final String REOPENED_EVENT = "billing.day.reopened.v1";
    static final String TOLERANCE_POLICY = "cash_variance_tolerance";
    // Used only while the clinic has no cash_variance_tolerance policy row (minor units): wireframe ₹200 India, 20 in the Gulf currencies.
    private static final Map<String, Long> DEFAULT_TOLERANCE_MINOR = Map.of("INR", 20_000L, "SAR", 2_000L, "AED", 2_000L, "QAR", 2_000L);
    private static final int HISTORY_DEFAULT_DAYS = 31;

    private final DayCloseRepository repo;
    private final TenantContext tenantContext;
    private final ClinicClock clock;
    private final AuditService auditService;
    private final ClinicPolicies setupService;
    private final EventPublisher eventPublisher;

    private final ActorDirectory actors;
    private final com.nabd.hms.organization.api.RegionRules regions;

    DayCloseService(DayCloseRepository repo, TenantContext tenantContext, ClinicClock clock, AuditService auditService,
                    ClinicPolicies setupService, EventPublisher eventPublisher, ActorDirectory actors,
                    com.nabd.hms.organization.api.RegionRules regions) {
        this.regions = regions;
        this.actors = actors;
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.auditService = auditService;
        this.setupService = setupService;
        this.eventPublisher = eventPublisher;
    }

    /** The clinic's cash_variance_tolerance policy (minor units) or, without one, the currency's default. */
    static BigDecimal tolerance(String currency, Optional<String> policyMinor) {
        long minor = policyMinor.map(Long::parseLong).orElseGet(() -> DEFAULT_TOLERANCE_MINOR.getOrDefault(currency, 2_000L));
        return BigDecimal.valueOf(minor, 2);
    }

    private String currencyOf(UUID tenantId) {
        return regions.region(repo.region(tenantId).orElse("IN")).currency();
    }

    private BigDecimal toleranceOf(UUID tenantId, String currency) {
        return tolerance(currency, setupService.policyValue(tenantId, TOLERANCE_POLICY));
    }

    @Transactional(readOnly = true)
    public DayCloseSummaryResponse summary(UUID tenantId, LocalDate date) {
        tenantContext.set(tenantId);
        LocalDate today = clock.clinicDate(tenantId); // ACC-005: the clinic day, not the calendar date
        LocalDate day = date != null ? date : today;
        Instant start = clock.dayStart(tenantId, day);
        Instant end = clock.dayStart(tenantId, day.plusDays(1));
        String currency = currencyOf(tenantId);
        DayCloseSummaryResponse.Close close = repo.findClose(tenantId, day).map(c -> new DayCloseSummaryResponse.Close(
                c.status(), c.expectedCash(), c.countedCash(), c.variance(), c.note(), c.closedByName(), c.closedAt(),
                c.reopenedByName(), c.reopenedAt(), c.reopenReason(), c.closeCount())).orElse(null);
        DayCloseRepository.Voided voided = repo.voided(tenantId, start, end);
        return new DayCloseSummaryResponse(day, day.equals(today), currency, "INR".equals(currency) ? "GST" : "VAT",
                toleranceOf(tenantId, currency), repo.totals(tenantId, start, end), repo.methods(tenantId, start, end),
                repo.outstanding(tenantId, start, end), repo.pendingCheckouts(tenantId, day), repo.unbilled(tenantId, day),
                repo.taxBands(tenantId, start, end), repo.splitPaymentBills(tenantId, start, end), close,
                voided.count(), voided.total(), repo.refunds(tenantId, start, end), repo.creditNotes(tenantId, start, end));
    }

    /** ADM-044: the reception desk's end-of-day summary. Scoped to the caller's clinic by tenant id and RLS; collections
     * and unbilled visits are the same queries as {@link #summary}, so the desk and the close show the same figures. */
    @Transactional(readOnly = true)
    public DeskSummaryResponse deskSummary(UUID tenantId, LocalDate date) {
        // tenant bound from the token at transaction start (TenantTransactionBinder); no TenantContext.set (PLT-019)
        LocalDate today = clock.clinicDate(tenantId);
        LocalDate day = date != null ? date : today;
        Instant start = clock.dayStart(tenantId, day);
        Instant end = clock.dayStart(tenantId, day.plusDays(1));
        List<DayCloseRepository.NoShowRow> noShows = repo.noShows(tenantId, start, end, clock.now());
        Map<UUID, ActorDirectory.Actor> doctors = actors.findActors(tenantId, noShows.stream().map(DayCloseRepository.NoShowRow::doctorId).toList());
        return new DeskSummaryResponse(day, day.equals(today), currencyOf(tenantId), repo.visits(tenantId, day),
                repo.methods(tenantId, start, end), repo.unbilled(tenantId, day),
                noShows.stream().map(n -> new DeskSummaryResponse.DeskNoShow(n.appointmentId(), n.patientName(),
                        doctors.containsKey(n.doctorId()) ? doctors.get(n.doctorId()).name() : null, n.startTime())).toList());
    }

    /** Closes a clinic day (default today; never a future day). Blocked while any ended consultation has no bill. */
    @Transactional
    public DayCloseSummaryResponse close(UUID tenantId, UUID staffId, DayCloseRequest req) {
        tenantContext.set(tenantId);
        LocalDate today = clock.clinicDate(tenantId); // ACC-005: the clinic day, not the calendar date
        LocalDate day = req.date() != null ? req.date() : today;
        if (day.isAfter(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "date-in-future", "Day hasn't happened yet",
                    "A day can only be closed once it has started. Pick today or an earlier day.");
        }
        repo.lockDay(tenantId, day);
        if (repo.isClosed(tenantId, day)) {
            throw new ApiException(HttpStatus.CONFLICT, "day-already-closed", "Day already closed",
                    day + " is already closed. Unlock it first to change anything.");
        }
        List<DayCloseSummaryResponse.Unbilled> unbilled = repo.unbilled(tenantId, day);
        if (!unbilled.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "day-close-blocked", "Close blocked",
                    unbilled.size() + (unbilled.size() == 1 ? " completed consultation still has" : " completed consultations still have")
                            + " no bill. Bill each one before the day can close.");
        }
        Instant start = clock.dayStart(tenantId, day);
        Instant end = clock.dayStart(tenantId, day.plusDays(1));
        BigDecimal expected = repo.methods(tenantId, start, end).stream().filter(m -> "cash".equals(m.method()))
                .findFirst().map(DayCloseSummaryResponse.Method::amount).orElse(BigDecimal.ZERO);
        BigDecimal variance = req.countedCash().subtract(expected);
        String currency = currencyOf(tenantId);
        BigDecimal tolerance = toleranceOf(tenantId, currency);
        String note = req.note() == null || req.note().isBlank() ? null : req.note().strip();
        if (variance.abs().compareTo(tolerance) > 0 && note == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "variance-note-required", "Note required",
                    "The cash count is off by more than " + tolerance.toPlainString() + ". Add a note explaining the difference.");
        }
        DayCloseRepository.Closed closed = repo.upsertClose(tenantId, day, expected, req.countedCash(), variance, note, staffId);
        repo.insertEvent(tenantId, closed.id(), "close", expected, req.countedCash(), variance, note, staffId);
        audit(tenantId, staffId, "day_close.close", closed.id(), Map.of("date", day.toString(), "expectedCash", expected,
                "countedCash", req.countedCash(), "variance", variance, "closeCount", closed.closeCount()));
        eventPublisher.publish(tenantId, CLOSED_EVENT, Map.of("tenantId", tenantId, "date", day.toString(),
                "expectedCashMinor", minor(expected), "countedCashMinor", minor(req.countedCash()), "varianceMinor", minor(variance),
                "currency", currency, "closeCount", closed.closeCount()));
        return summary(tenantId, day);
    }

    /** Unlocks a closed day (default: today) so billing can be corrected; the reason is audited. */
    @Transactional
    public DayCloseSummaryResponse reopen(UUID tenantId, UUID staffId, LocalDate date, DayReopenRequest req) {
        tenantContext.set(tenantId);
        LocalDate day = req.date() != null ? req.date() : date != null ? date : clock.clinicDate(tenantId);
        repo.lockDay(tenantId, day);
        DayCloseRepository.CloseRow row = repo.findClose(tenantId, day).filter(c -> "closed".equals(c.status()))
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "day-not-closed", "Day isn't closed",
                        "There's no closed day to unlock on " + day + "."));
        String reason = req.reason().strip();
        repo.reopen(tenantId, row.id(), staffId, reason);
        repo.insertEvent(tenantId, row.id(), "reopen", null, null, null, reason, staffId);
        audit(tenantId, staffId, "day_close.reopen", row.id(), Map.of("date", day.toString(), "reason", reason));
        // Free-text reason stays out of the event (PHI-free payload); consumers read audit_log if they need it.
        eventPublisher.publish(tenantId, REOPENED_EVENT, Map.of("tenantId", tenantId, "date", day.toString()));
        return summary(tenantId, day);
    }

    /** Close/reopen history for [from, to] (defaults: the last 31 days to today), newest first, keyset-paged. */
    @Transactional(readOnly = true)
    public DayCloseHistoryResponse history(UUID tenantId, LocalDate from, LocalDate to, String cursor, int limit) { // tenant bound at transaction start (PLT-019)
        LocalDate end = to != null ? to : clock.clinicDate(tenantId);
        LocalDate begin = from != null ? from : end.minusDays(HISTORY_DEFAULT_DAYS);
        if (begin.isAfter(end)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-range", "Invalid range", "from must be on or before to.");
        }
        Instant beforeAt = null;
        UUID beforeId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] parts = cursor.split("_", 2);
                beforeAt = Instant.parse(parts[0]);
                beforeId = UUID.fromString(parts[1]);
            } catch (RuntimeException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-cursor", "Invalid cursor", "The cursor isn't one this endpoint returned.");
            }
        }
        limit = Math.max(1, Math.min(limit, 100));
        List<DayCloseRepository.HistoryRow> found = repo.history(tenantId, begin, end, beforeAt, beforeId, limit + 1);
        Map<UUID, ActorDirectory.Actor> names = actors.findActors(tenantId, found.stream().map(DayCloseRepository.HistoryRow::actorId).toList());
        List<DayCloseHistoryResponse.Item> rows = found.stream().map(r -> r.withActorName(
                names.containsKey(r.actorId()) ? names.get(r.actorId()).name() : null)).toList();
        List<DayCloseHistoryResponse.Item> page = rows.size() > limit ? rows.subList(0, limit) : rows;
        String next = rows.size() > limit ? page.get(limit - 1).at() + "_" + page.get(limit - 1).id() : null;
        return new DayCloseHistoryResponse(List.copyOf(page), next);
    }

    /** Billing writes land on the clinic's today; once today is closed they'd change a reconciled day. Takes the
     * tenant-day lock first, so a write racing a close either commits before the close counts it or sees it closed.
     * Callers: checkout, OTC and package sale, payment, void, package refund approval. */
    public void requireTodayOpen(UUID tenantId) {
        LocalDate today = clock.clinicDate(tenantId);
        repo.lockDay(tenantId, today);
        if (repo.isClosed(tenantId, today)) {
            throw new ApiException(HttpStatus.CONFLICT, "day-closed", "Day is closed",
                    "Today's billing is closed. Ask the owner to unlock the day before billing or taking payments.");
        }
    }

    private static long minor(BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }

    private void audit(UUID tenantId, UUID staffId, String action, UUID entityId, Object after) {
        String[] actor = actors.findActor(tenantId, staffId).map(a -> new String[]{a.name(), a.role()}).orElse(new String[]{"Unknown", "Unknown"});
        auditService.record(tenantId, "staff", staffId, actor[0], actor[1], null, action, "day_close", entityId, null, after);
    }
}
