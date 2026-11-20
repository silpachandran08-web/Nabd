package com.nabd.hms.reports;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.RegionRules;
import com.nabd.hms.reports.ReportsModels.NoShowRiskRow;
import com.nabd.hms.reports.ReportsModels.StaffCollectionRow;
import com.nabd.hms.reports.dto.ReportExportRequest;
import com.nabd.hms.reports.dto.ReportExportResponse;
import com.nabd.hms.reports.dto.ReportFrame;
import com.nabd.hms.reports.dto.ReportFrame.Chart;
import com.nabd.hms.reports.dto.ReportFrame.Column;
import com.nabd.hms.reports.dto.ReportFrame.Kpi;
import com.nabd.hms.reports.dto.ReportFrame.Series;
import com.nabd.hms.staff.StaffService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * ADM-047: the reports library. One registry, one frame (ReportFrame), one audited export path. Aggregates (KPIs, charts) come from
 * the OPS-010 rollups where the figure lives there (money, tax, visits, no-shows, doctor totals); the rest are live reads named in
 * each rule text. Drill-down rows are paged 50 at a time. The legacy per-report endpoints in ReportsController stay as they were.
 * shortcut: the replica (T-208) and the nabd_reports 60 s timeout are not wired, every read uses the app's own pool; upgrade when the replica exists.
 */
@Service
public class ReportLibraryService {

    static final int PAGE = 50;
    static final int EXPORT_CAP = 10_000;
    static final int MAX_RANGE_DAYS = 366;
    private static final int NO_SHOW_LOOKBACK_DAYS = 90;
    private static final int NO_SHOW_THRESHOLD = 2;
    private static final int CHART_CATEGORIES = 12;

    record Def(String id, String group, String title, String rule, String requires) {
    }

    /** Registry in library order. Keys avoid the legacy literal paths (money, sources, retention ...) so both stay reachable. */
    static final Map<String, Def> REGISTRY = new LinkedHashMap<>();

    static {
        reg("leakage", "money", "Billing leakage",
                "Procedures marked done whose charge never appears on that visit's invoice, amount at or above the threshold, completed in the range.", null);
        reg("takings", "money", "Daily money",
                "Collected and billed per clinic day from the daily rollups (today is read live); rows are the payments recorded in the range.", null);
        reg("package-liability", "money", "Package liability",
                "Money already collected for package sessions not yet delivered, valued at each item's allocated price. As of now; the range does not apply.", "packages:view");
        reg("tax-summary", "money", "Tax summary",
                "Tax recorded on non-void invoices per clinic day (today is read live). A summary of what was billed, not a filed return: rates and filing treatment need the clinic's accountant.", null);
        reg("patient-sources", "patients", "Where patients come from",
                "Visits in the range grouped by the source recorded at check-in; revenue is the invoice totals of those visits.", null);
        reg("patient-retention", "patients", "Retention",
                "Aggregate only: repeat patients are those with two or more completed visits to date. No individual patient list is returned.", null);
        reg("no-show-watch", "patients", "No-show risk",
                "Patients in today's queue with " + NO_SHOW_THRESHOLD + " or more no-shows in the last " + NO_SHOW_LOOKBACK_DAYS + " days.", null);
        reg("staff-activity", "team", "Staff performance",
                "Payments collected, visits handled, discounted invoices and prescription overrides per staff member in the range.", null);
        reg("doctor-productivity", "team", "Doctor productivity",
                "Visits, completions, no-shows, billed and collected per doctor from the daily rollups. Billed for a past day catches up on the next event of that day.", null);
        reg("doctor-on-time", "team", "Doctor punctuality",
                "Doctor-announced delays in the range and their average length. Session start times are not recorded yet, so lateness against a 5-minute rule cannot be measured.", null);
    }

    private static void reg(String id, String group, String title, String rule, String requires) {
        REGISTRY.put(id, new Def(id, group, title, rule, requires));
    }

    /** Everything one report needs, resolved once per request. */
    private record Ctx(UUID tenant, UUID staff, Def def, LocalDate from, LocalDate to, ZoneId zone, LocalDate today, Instant start, Instant end,
                       boolean teamView, boolean patientNames, BigDecimal threshold, int limit, String cursor, String currency) {
    }

    private final ReportLibraryRepository lib;
    private final ReportsRepository repo;
    private final StaffService staffService;
    private final AuditService audit;
    private final EventPublisher events;
    private final ActorDirectory actors;
    private final ClinicClock clock;
    private final RegionRules regions;

    ReportLibraryService(ReportLibraryRepository lib, ReportsRepository repo, StaffService staffService, AuditService audit, EventPublisher events,
                         ActorDirectory actors, ClinicClock clock, RegionRules regions) {
        this.lib = lib;
        this.repo = repo;
        this.staffService = staffService;
        this.audit = audit;
        this.events = events;
        this.actors = actors;
        this.clock = clock;
        this.regions = regions;
    }

    // ── access ──

    static boolean has(String authority) {
        var a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getAuthorities().stream().anyMatch(g -> authority.equals(g.getAuthority()));
    }

    /** Team reports show every staff member's row only to a caller who manages staff and is not scoped to their own patients. */
    static boolean teamView(String scope, boolean holdsStaffView) {
        return holdsStaffView && !"own_patients_only".equals(scope);
    }

    /** First letter of each word and asterisks: enough to recognise a name on paper, not to read it. */
    static String mask(String name) {
        if (name == null || name.isBlank()) return "";
        StringBuilder b = new StringBuilder();
        for (String w : name.trim().split("\\s+")) {
            if (b.length() > 0) b.append(' ');
            b.appendCodePoint(w.codePointAt(0)).append("***");
        }
        return b.toString();
    }

    // ── run ──

    @Transactional(readOnly = true)
    public ReportFrame run(UUID tenantId, UUID staffId, String reportId, LocalDate from, LocalDate to, UUID branch, String cursor, BigDecimal threshold) {
        return build(ctx(tenantId, staffId, reportId, from, to, branch, cursor, threshold, PAGE));
    }

    private Ctx ctx(UUID tenantId, UUID staffId, String reportId, LocalDate from, LocalDate to, UUID branch, String cursor, BigDecimal threshold, int limit) {
        Def def = REGISTRY.get(reportId);
        if (def == null) throw ApiException.notFound("Unknown report.");
        if (def.requires() != null && !has(def.requires())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Not allowed", "Your role cannot open this report.");
        }
        if (branch != null && !branch.equals(tenantId)) throw ApiException.notFound("Unknown branch."); // a facility is its own tenant: never another one
        ZoneId zone = clock.zone(tenantId);
        LocalDate today = clock.today(zone);
        LocalDate f = from != null ? from : to != null ? to : today.minusDays(29);
        LocalDate t = to != null ? to : from != null ? from : today;
        if (t.isBefore(f)) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-period", "Invalid period", "The start date must be on or before the end date.");
        if (ChronoUnit.DAYS.between(f, t) + 1 > MAX_RANGE_DAYS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RANGE_TOO_LONG", "Range too long", "Pick at most " + MAX_RANGE_DAYS + " days.");
        }
        var caller = staffService.getCallerInfo(tenantId, staffId);
        String currency = regions.region(repo.region(tenantId).orElse("IN")).currency();
        BigDecimal min = threshold == null || threshold.signum() < 0 ? BigDecimal.ZERO : threshold;
        return new Ctx(tenantId, staffId, def, f, t, zone, today, ClinicClock.startOf(f, zone), ClinicClock.startOf(t.plusDays(1), zone),
                teamView(caller.scope(), has("staff:view")), has("patients:view"), min, limit, cursor, currency);
    }

    private ReportFrame build(Ctx c) {
        return switch (c.def().id()) {
            case "leakage" -> leakage(c);
            case "takings" -> takings(c);
            case "package-liability" -> packageLiability(c);
            case "tax-summary" -> taxSummary(c);
            case "patient-sources" -> patientSources(c);
            case "patient-retention" -> retention(c);
            case "no-show-watch" -> noShowWatch(c);
            case "staff-activity" -> staffActivity(c);
            case "doctor-productivity" -> doctorProductivity(c);
            default -> doctorOnTime(c);
        };
    }

    // ── reports ──

    private ReportFrame leakage(Ctx c) {
        Key after = key(c.cursor());
        List<Map<String, Object>> raw = lib.leakage(c.tenant(), c.start(), c.end(), c.threshold(), after.instant(), after.id(), c.limit() + 1);
        List<Map<String, Object>> byDay = lib.leakageByDay(c.tenant(), c.start(), c.end(), c.threshold(), c.zone());
        long n = byDay.stream().mapToLong(r -> ((Number) r.get("n")).longValue()).sum();
        BigDecimal value = byDay.stream().map(r -> num(r.get("amount"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<LocalDate, BigDecimal> perDay = new LinkedHashMap<>();
        byDay.forEach(r -> perDay.put((LocalDate) r.get("day"), num(r.get("amount"))));
        Paged p = keyset(raw, c.limit(), "completed_at");
        return frame(c, List.of(new Kpi("items", "Items done but not billed", n, "count"), new Kpi("value", "Value at risk", value, "money"),
                        new Kpi("threshold", "Threshold", c.threshold(), "money")),
                dayChart("Unbilled value per day", c, perDay),
                cols("at", "Completed", "datetime", "patient", "Patient", "text", "code", "Charge code", "text", "charge", "Charge", "text", "amount", "Amount", "money"),
                p.rows().stream().map(r -> row("at", r.get("completed_at"), "patient", r.get("patient"), "code", r.get("code"), "charge", r.get("charge"),
                        "amount", r.get("amount"))).toList(), p.next(), null, null, false);
    }

    private ReportFrame takings(Ctx c) {
        Map<LocalDate, BigDecimal[]> days = moneyByDay(c);
        BigDecimal billed = days.values().stream().map(a -> a[0]).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal collected = days.values().stream().map(a -> a[1]).reduce(BigDecimal.ZERO, BigDecimal::add);
        Key after = key(c.cursor());
        Paged p = keyset(lib.payments(c.tenant(), c.start(), c.end(), after.instant(), after.id(), c.limit() + 1), c.limit(), "recorded_at");
        Map<LocalDate, BigDecimal> perDay = new LinkedHashMap<>();
        days.forEach((d, a) -> perDay.put(d, a[1]));
        return frame(c, List.of(new Kpi("collected", "Collected", collected, "money"), new Kpi("billed", "Billed", billed, "money"),
                        new Kpi("outstanding", "Dues outstanding now", repo.outstandingTotal(c.tenant()), "money"),
                        new Kpi("payments", "Payments recorded", (long) repo.paymentCountOn(c.tenant(), c.start(), c.end()), "count")),
                dayChart("Collected per day", c, perDay),
                cols("at", "Recorded", "datetime", "invoice", "Invoice", "text", "patient", "Patient", "text", "method", "Method", "text",
                        "amount", "Amount", "money", "by", "Recorded by", "text"),
                p.rows().stream().map(r -> row("at", r.get("recorded_at"), "invoice", r.get("invoice"), "patient", r.get("patient"), "method", r.get("method"),
                        "amount", r.get("amount"), "by", r.get("recorded_by"))).toList(), p.next(), null, asOf(c), false);
    }

    private ReportFrame taxSummary(Ctx c) {
        Map<LocalDate, BigDecimal[]> days = moneyByDay(c);
        BigDecimal tax = days.values().stream().map(a -> a[2]).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal billed = days.values().stream().map(a -> a[0]).reduce(BigDecimal.ZERO, BigDecimal::add);
        Key after = key(c.cursor());
        Paged p = keyset(lib.taxInvoices(c.tenant(), c.start(), c.end(), after.instant(), after.id(), c.limit() + 1), c.limit(), "created_at");
        Map<LocalDate, BigDecimal> perDay = new LinkedHashMap<>();
        days.forEach((d, a) -> perDay.put(d, a[2]));
        return frame(c, List.of(new Kpi("tax", "Tax billed", tax, "money"), new Kpi("billed", "Billed incl. tax", billed, "money"),
                        new Kpi("invoices", "Invoices with tax", lib.taxInvoiceCount(c.tenant(), c.start(), c.end()), "count")),
                dayChart("Tax billed per day", c, perDay),
                cols("at", "Invoiced", "datetime", "invoice", "Invoice", "text", "subtotal", "Subtotal", "money", "discount", "Discount", "money",
                        "tax", "Tax", "money", "total", "Total", "money", "status", "Status", "text"),
                p.rows().stream().map(r -> row("at", r.get("created_at"), "invoice", r.get("invoice"), "subtotal", r.get("subtotal"), "discount", r.get("discount"),
                        "tax", r.get("tax"), "total", r.get("total"), "status", r.get("status"))).toList(), p.next(), null, asOf(c), false);
    }

    private ReportFrame packageLiability(Ctx c) {
        int off = offset(c.cursor());
        List<Map<String, Object>> raw = lib.packages(c.tenant(), c.limit() + 1, off);
        Map<String, Object> t = lib.packageTotals(c.tenant());
        boolean more = raw.size() > c.limit();
        return frame(c, List.of(new Kpi("packages", "Active packages with sessions left", num(t.get("packages")).longValue(), "count"),
                        new Kpi("sessions", "Sessions owed", num(t.get("sessions")).longValue(), "count"),
                        new Kpi("value", "Service still owed", num(t.get("value")).setScale(2, RoundingMode.HALF_UP), "money")), null,
                cols("patient", "Patient", "text", "package", "Package", "text", "validTo", "Valid until", "date", "left", "Sessions left", "number", "value", "Value left", "money"),
                raw.stream().limit(c.limit()).map(r -> row("patient", r.get("patient"), "package", r.get("package"), "validTo", r.get("valid_to"),
                        "left", r.get("sessions_left"), "value", r.get("value_left"))).toList(), more ? String.valueOf(off + c.limit()) : null, null, clock.now(), false);
    }

    private ReportFrame patientSources(Ctx c) {
        var sources = repo.sourceBreakdown(c.tenant(), c.from(), c.to());
        List<Map<String, Object>> rollups = lib.dayRollups(c.tenant(), c.from(), c.to());
        long visits = rollups.stream().mapToLong(r -> num(r.get("visits")).longValue()).sum();
        BigDecimal revenue = sources.stream().map(ReportsModels.SourceCount::revenue).reduce(BigDecimal.ZERO, BigDecimal::add);
        Key after = key(c.cursor());
        LocalDate afterDay = after.k() == null ? null : LocalDate.parse(after.k());
        List<Map<String, Object>> raw = lib.visits(c.tenant(), c.from(), c.to(), afterDay, after.id(), c.limit() + 1);
        boolean more = raw.size() > c.limit();
        List<Map<String, Object>> page = raw.subList(0, Math.min(raw.size(), c.limit()));
        String next = more ? enc(page.get(page.size() - 1).get("day").toString(), (UUID) page.get(page.size() - 1).get("id")) : null;
        List<String> cats = sources.stream().limit(CHART_CATEGORIES).map(ReportsModels.SourceCount::source).toList();
        Chart chart = sources.isEmpty() ? null : new Chart("bar", "Visits by source", false, cats,
                List.of(new Series("visits", "Visits", sources.stream().limit(CHART_CATEGORIES).map(s -> BigDecimal.valueOf(s.visitCount())).toList())));
        return frame(c, List.of(new Kpi("visits", "Visits", visits, "count"), new Kpi("revenue", "Billed from these visits", revenue, "money"),
                        new Kpi("untagged", "Visits with no source", repo.untaggedVisits(c.tenant(), c.from(), c.to()), "count")), chart,
                cols("day", "Date", "date", "token", "Token", "number", "source", "Source", "text", "patient", "Patient", "text", "status", "Status", "text"),
                page.stream().map(r -> row("day", r.get("day"), "token", r.get("token"), "source", r.get("source"), "patient", r.get("patient"),
                        "status", r.get("status"))).toList(), next, null, asOf(c, rollups), false);
    }

    private ReportFrame retention(Ctx c) {
        int visited = repo.patientsWithAtLeastOneVisit(c.tenant());
        int repeat = repo.repeatPatientCount(c.tenant());
        int completed = repo.completedVisitCount(c.tenant());
        List<Map<String, Object>> rollups = lib.dayRollups(c.tenant(), c.from(), c.to());
        Map<LocalDate, BigDecimal> perDay = zeroDays(c);
        rollups.forEach(r -> perDay.put((LocalDate) r.get("day"), num(r.get("completed"))));
        BigDecimal rate = visited == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(100.0 * repeat / visited).setScale(1, RoundingMode.HALF_UP);
        BigDecimal avg = visited == 0 ? BigDecimal.ZERO : BigDecimal.valueOf((double) completed / visited).setScale(1, RoundingMode.HALF_UP);
        return frame(c, List.of(new Kpi("patients", "Patients with a completed visit", (long) visited, "count"), new Kpi("repeat", "Came back at least once", (long) repeat, "count"),
                        new Kpi("rate", "Repeat rate", rate, "percent"), new Kpi("avgVisits", "Visits per patient", avg, "number"),
                        new Kpi("inRange", "Completed visits in range", perDay.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).longValue(), "count")),
                dayChart("Completed visits per day", c, perDay), List.of(), List.of(), null, null, asOf(c, rollups), true);
    }

    private ReportFrame noShowWatch(Ctx c) {
        List<NoShowRiskRow> flagged = repo.noShowRiskToday(c.tenant(), c.today(), c.today().minusDays(NO_SHOW_LOOKBACK_DAYS), NO_SHOW_THRESHOLD);
        List<Map<String, Object>> rollups = lib.dayRollups(c.tenant(), c.from(), c.to());
        Map<LocalDate, BigDecimal> perDay = zeroDays(c);
        rollups.forEach(r -> perDay.put((LocalDate) r.get("day"), num(r.get("no_shows"))));
        int off = offset(c.cursor());
        List<Map<String, Object>> rows = flagged.stream().skip(off).limit(c.limit()).map(r -> row("patient", r.patientName(), "prior", r.priorNoShowCount())).toList();
        return frame(c, List.of(new Kpi("flagged", "Patients flagged in today's queue", (long) flagged.size(), "count"),
                        new Kpi("noShows", "No-shows in range", perDay.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).longValue(), "count")),
                dayChart("No-shows per day", c, perDay), cols("patient", "Patient", "text", "prior", "No-shows in 90 days", "number"), rows,
                flagged.size() > off + c.limit() ? String.valueOf(off + c.limit()) : null, null, asOf(c, rollups), false);
    }

    private ReportFrame staffActivity(Ctx c) {
        List<StaffCollectionRow> all = repo.staffCollection(c.tenant(), c.from(), c.to(), c.start(), c.end(), c.teamView() ? null : c.staff());
        int off = offset(c.cursor());
        List<Map<String, Object>> rows = all.stream().skip(off).limit(c.limit()).map(r -> row("staff", r.staffName(), "collected", r.collected(),
                "payments", r.paymentCount(), "handled", r.handledCount(), "discounts", r.discountCount(), "overrides", r.overrideCount())).toList();
        BigDecimal collected = all.stream().map(StaffCollectionRow::collected).reduce(BigDecimal.ZERO, BigDecimal::add);
        return frame(c, List.of(new Kpi("staff", "Staff with activity", (long) all.size(), "count"), new Kpi("collected", "Collected", collected, "money"),
                        new Kpi("discounts", "Discounted invoices", all.stream().mapToLong(StaffCollectionRow::discountCount).sum(), "count"),
                        new Kpi("overrides", "Prescription overrides", all.stream().mapToLong(StaffCollectionRow::overrideCount).sum(), "count")),
                barChart("Collected per staff member", all, StaffCollectionRow::staffName, StaffCollectionRow::collected),
                cols("staff", "Staff", "text", "collected", "Collected", "money", "payments", "Payments", "number", "handled", "Visits handled", "number",
                        "discounts", "Discounts", "number", "overrides", "Overrides", "number"),
                rows, all.size() > off + c.limit() ? String.valueOf(off + c.limit()) : null, teamNote(c), null, false);
    }

    private ReportFrame doctorProductivity(Ctx c) {
        List<Map<String, Object>> all = lib.doctorRollups(c.tenant(), c.from(), c.to(), c.teamView() ? null : c.staff());
        int off = offset(c.cursor());
        List<Map<String, Object>> rows = all.stream().skip(off).limit(c.limit()).map(r -> row("doctor", r.get("doctor"), "visits", r.get("visits"),
                "completed", r.get("completed"), "noShows", r.get("no_shows"), "billed", r.get("billed"), "collected", r.get("collected"))).toList();
        return frame(c, List.of(new Kpi("doctors", "Doctors with activity", (long) all.size(), "count"), new Kpi("visits", "Visits", sum(all, "visits").longValue(), "count"),
                        new Kpi("completed", "Completed", sum(all, "completed").longValue(), "count"), new Kpi("collected", "Collected", sum(all, "collected"), "money")),
                barChart("Collected per doctor", all, r -> (String) r.get("doctor"), r -> num(r.get("collected"))),
                cols("doctor", "Doctor", "text", "visits", "Visits", "number", "completed", "Completed", "number", "noShows", "No-shows", "number",
                        "billed", "Billed", "money", "collected", "Collected", "money"),
                rows, all.size() > off + c.limit() ? String.valueOf(off + c.limit()) : null, teamNote(c), asOf(c, all), false);
    }

    private ReportFrame doctorOnTime(Ctx c) {
        List<Map<String, Object>> all = lib.doctorDelays(c.tenant(), c.start(), c.end(), c.zone(), c.teamView() ? null : c.staff());
        long delays = sum(all, "delays").longValue();
        BigDecimal weighted = all.stream().map(r -> num(r.get("avg_minutes")).multiply(num(r.get("delays")))).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avg = delays == 0 ? BigDecimal.ZERO : weighted.divide(BigDecimal.valueOf(delays), 1, RoundingMode.HALF_UP);
        int off = offset(c.cursor());
        List<Map<String, Object>> rows = all.stream().skip(off).limit(c.limit()).map(r -> row("doctor", r.get("doctor"), "delays", r.get("delays"),
                "avg", num(r.get("avg_minutes")).setScale(1, RoundingMode.HALF_UP), "repeat", r.get("repeat_days"))).toList();
        return frame(c, List.of(new Kpi("doctors", "Doctors with delays", (long) all.size(), "count"), new Kpi("delays", "Delays announced", delays, "count"),
                        new Kpi("avg", "Average delay", avg, "minutes")),
                barChart("Delays per doctor", all, r -> (String) r.get("doctor"), r -> num(r.get("delays"))),
                cols("doctor", "Doctor", "text", "delays", "Delays", "number", "avg", "Average (min)", "number", "repeat", "Days with 2+ delays", "number"),
                rows, all.size() > off + c.limit() ? String.valueOf(off + c.limit()) : null, teamNote(c), null, false);
    }

    // ── frame helpers ──

    private ReportFrame frame(Ctx c, List<Kpi> kpis, Chart chart, List<Column> columns, List<Map<String, Object>> rows, String next, String scopeNote,
                              Instant asOf, boolean aggregateOnly) {
        boolean masked = !c.patientNames() && columns.stream().anyMatch(x -> "patient".equals(x.key()));
        List<Map<String, Object>> shown = masked ? rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.computeIfPresent("patient", (k, v) -> mask(v.toString()));
            return m;
        }).toList() : rows;
        return new ReportFrame(c.def().id(), c.def().group(), c.def().title(), c.from(), c.to(), c.tenant(), c.def().rule(), scopeNote, asOf, c.currency(),
                kpis, chart, columns, shown, next, masked, aggregateOnly);
    }

    private static String teamNote(Ctx c) {
        return c.teamView() ? "You can see every staff member's row." : "You can see only your own row.";
    }

    /** [billed, collected, tax] per clinic day: the rollup rows, zero-filled, with today replaced by the live figures. */
    private Map<LocalDate, BigDecimal[]> moneyByDay(Ctx c) {
        Map<LocalDate, BigDecimal[]> m = new LinkedHashMap<>();
        for (LocalDate d = c.from(); !d.isAfter(c.to()); d = d.plusDays(1)) m.put(d, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
        for (Map<String, Object> r : lib.dayRollups(c.tenant(), c.from(), c.to())) {
            m.put((LocalDate) r.get("day"), new BigDecimal[]{num(r.get("billed")), num(r.get("collected")), num(r.get("tax"))});
        }
        if (m.containsKey(c.today())) {
            Instant s = ClinicClock.startOf(c.today(), c.zone());
            Instant e = ClinicClock.startOf(c.today().plusDays(1), c.zone());
            m.put(c.today(), new BigDecimal[]{repo.billedOn(c.tenant(), s, e), repo.collectedOn(c.tenant(), s, e), repo.taxBilledBetween(c.tenant(), s, e)});
        }
        return m;
    }

    private Instant asOf(Ctx c) {
        return asOf(c, lib.dayRollups(c.tenant(), c.from(), c.to()));
    }

    /** When the figures were last refreshed: now if today is in the range (live), else the newest rollup row. */
    private Instant asOf(Ctx c, List<Map<String, Object>> rollupRows) {
        if (!c.today().isBefore(c.from()) && !c.today().isAfter(c.to())) return clock.now();
        return rollupRows.stream().map(r -> (Instant) r.get("updated_at")).filter(java.util.Objects::nonNull).max(Instant::compareTo).orElse(null);
    }

    private Map<LocalDate, BigDecimal> zeroDays(Ctx c) {
        Map<LocalDate, BigDecimal> m = new LinkedHashMap<>();
        for (LocalDate d = c.from(); !d.isAfter(c.to()); d = d.plusDays(1)) m.put(d, BigDecimal.ZERO);
        return m;
    }

    private Chart dayChart(String title, Ctx c, Map<LocalDate, BigDecimal> perDay) {
        Map<LocalDate, BigDecimal> m = new LinkedHashMap<>(zeroDays(c));
        m.putAll(perDay);
        return new Chart("column", title, true, m.keySet().stream().map(LocalDate::toString).toList(), List.of(new Series("value", title, new ArrayList<>(m.values()))));
    }

    private <T> Chart barChart(String title, List<T> items, Function<T, String> label, Function<T, BigDecimal> value) {
        if (items.isEmpty()) return null;
        List<T> top = items.stream().limit(CHART_CATEGORIES).toList();
        return new Chart("bar", title, false, top.stream().map(label).toList(), List.of(new Series("value", title, top.stream().map(value).toList())));
    }

    private static BigDecimal sum(List<Map<String, Object>> rows, String key) {
        return rows.stream().map(r -> num(r.get(key))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal num(Object o) {
        return o == null ? BigDecimal.ZERO : o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }

    private static List<Column> cols(String... kvt) {
        List<Column> l = new ArrayList<>();
        for (int i = 0; i < kvt.length; i += 3) l.add(new Column(kvt[i], kvt[i + 1], kvt[i + 2]));
        return l;
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ── cursors: keyset = Base64Url("<sort key>|<id>"), offset = the number of rows already shown ──

    private record Key(String k, UUID id) {
        Instant instant() {
            return k == null ? null : Instant.parse(k);
        }
    }

    private record Paged(List<Map<String, Object>> rows, String next) {
    }

    private static String enc(String k, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((k + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    private static Key key(String cursor) {
        if (cursor == null || cursor.isBlank()) return new Key(null, null);
        try {
            String[] p = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
            if (p.length != 2) throw new IllegalArgumentException();
            return new Key(p[0], UUID.fromString(p[1]));
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-cursor", "Invalid cursor", "That page marker is not valid. Reload the report.");
        }
    }

    private static int offset(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            int n = Integer.parseInt(cursor);
            if (n < 0) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-cursor", "Invalid cursor", "That page marker is not valid. Reload the report.");
        }
    }

    /** Rows were read limit+1: more than limit means another page, whose cursor is the last row kept. */
    private static Paged keyset(List<Map<String, Object>> raw, int limit, String timeColumn) {
        if (raw.size() <= limit) return new Paged(raw, null);
        List<Map<String, Object>> page = raw.subList(0, limit);
        Map<String, Object> last = page.get(limit - 1);
        return new Paged(page, enc(last.get(timeColumn).toString(), (UUID) last.get("id")));
    }

    // ── export ──

    /** One audited path for Excel, CSV and PDF: the frame is built exactly as on screen (same filters and masking), then the file, the audit row and the event. */
    @Transactional
    public ReportExportResponse export(UUID tenantId, UUID staffId, String ip, String reportId, ReportExportRequest req) {
        Ctx c = ctx(tenantId, staffId, reportId, req.from(), req.to(), req.branch(), null, req.threshold(), EXPORT_CAP);
        ReportFrame f = build(c);
        if (f.nextCursor() != null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EXPORT_TOO_LARGE", "Too many rows", "This report has more than " + EXPORT_CAP + " rows. Narrow the range.");
        }
        byte[] bytes;
        String type;
        switch (req.format()) {
            case "csv" -> {
                bytes = ReportFiles.csv(f);
                type = "text/csv";
            }
            case "xlsx" -> {
                bytes = ReportFiles.xlsx(f);
                type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            }
            default -> {
                bytes = ReportFiles.pdf(f);
                type = "application/pdf";
            }
        }
        UUID exportId = UUID.randomUUID();
        ActorDirectory.Actor actor = actors.findActor(tenantId, staffId).orElseThrow(ApiException::notFound);
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("reportId", reportId);
        filters.put("format", req.format());
        filters.put("from", f.from().toString());
        filters.put("to", f.to().toString());
        filters.put("branch", f.branch().toString());
        if (c.def().id().equals("leakage")) filters.put("threshold", c.threshold().toPlainString());
        filters.put("rowCount", f.rows().size());
        filters.put("masked", f.masked());
        audit.record(tenantId, "staff", staffId, actor.name(), actor.role(), ip, "report.export", "report", exportId, null, filters);
        events.publish(tenantId, DomainEventType.REPORTING_EXPORT_CREATED, exportId,
                Map.of("exportId", exportId, "reportId", reportId, "actor", staffId, "format", req.format(), "rows", f.rows().size()));
        String name = reportId + "-" + f.from() + "-" + f.to() + "." + req.format();
        return new ReportExportResponse(exportId, reportId, req.format(), name, type, f.rows().size(), f.masked(), Base64.getEncoder().encodeToString(bytes));
    }
}
