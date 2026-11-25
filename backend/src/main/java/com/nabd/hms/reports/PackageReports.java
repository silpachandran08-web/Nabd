package com.nabd.hms.reports;

import com.nabd.hms.packages.api.PackageLedgerQueries;
import com.nabd.hms.packages.api.PackageLedgerQueries.Sold;
import com.nabd.hms.patient.api.MarketingAudience;
import com.nabd.hms.patient.api.PatientSummaries;
import com.nabd.hms.reports.dto.ReportFrame.Chart;
import com.nabd.hms.reports.dto.ReportFrame.Column;
import com.nabd.hms.reports.dto.ReportFrame.Kpi;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.nabd.hms.reports.ReportLibraryService.barChart;
import static com.nabd.hms.reports.ReportLibraryService.cols;
import static com.nabd.hms.reports.ReportLibraryService.row;

/**
 * ADM-049: package sales, redemption and expiry loss for the reports library. Facts come from the packages module's API
 * (PackageLedgerQueries); who may be sent a win-back comes from the patient module's marketing audience (IDC-008). The
 * aggregation below is pure, so the totals are the sums of the same facts the rows show.
 */
@Component
class PackageReports {

    static final String SALES = "package-sales", REDEMPTION = "package-redemption", EXPIRY = "package-expiry-loss";
    static final int LOW_AFTER_DAYS = 90;

    /** The parts of a frame this class decides; the library adds the header, paging, masking and export. */
    record Part(List<Kpi> kpis, Chart chart, List<Column> columns, List<Map<String, Object>> rows, String scopeNote) {
    }

    private final PackageLedgerQueries ledger;
    private final MarketingAudience audience;
    private final PatientSummaries patients;

    PackageReports(PackageLedgerQueries ledger, MarketingAudience audience, PatientSummaries patients) {
        this.ledger = ledger;
        this.audience = audience;
        this.patients = patients;
    }

    /** Called by ReportLibraryService inside its transaction (patient names come from the patient module's API).
     * @param matureBefore a package sold before this instant was sold at least LOW_AFTER_DAYS clinic days ago */
    Part build(String reportId, UUID tenantId, LocalDate from, LocalDate to, Instant start, Instant end, LocalDate today, Instant matureBefore) {
        if (EXPIRY.equals(reportId)) {
            List<Sold> lapsed = ledger.lapsedBetween(tenantId, from, to, today);
            Map<UUID, String> names = new HashMap<>();
            // shortcut: one lookup per lapsed patient; add a batch lookup to the patient module's API if a range holds thousands
            lapsed.forEach(s -> names.computeIfAbsent(s.patientId(), id -> patients.find(id).map(PatientSummaries.Summary::name).orElse("")));
            return expiry(lapsed, names, lapsed.isEmpty() ? Set.of() : marketingAudience());
        }
        List<Sold> sold = ledger.soldBetween(tenantId, start, end);
        return SALES.equals(reportId) ? sales(sold) : redemption(sold, matureBefore);
    }

    /** shortcut: reads the clinic's whole marketing audience, 1000 ids per query; ask the patient module for a "which of these ids"
     * lookup when a clinic's consented list makes this report slow. */
    private Set<UUID> marketingAudience() {
        Set<UUID> all = new HashSet<>();
        UUID after = null;
        List<UUID> page;
        do {
            page = audience.adults(after, 1000);
            all.addAll(page);
            if (!page.isEmpty()) after = page.get(page.size() - 1);
        } while (page.size() == 1000);
        return all;
    }

    // ── pure rules ──

    /** The low-redemption rule: fewer than half the sessions sold have been used. Applied only to sales at least 90 days old. */
    static boolean lowRedemption(long sessionsSold, long sessionsUsed) {
        return sessionsSold > 0 && sessionsUsed * 2 < sessionsSold;
    }

    /** Used sessions as a percentage of sold sessions, one decimal; 0 when nothing was sold. */
    static BigDecimal rate(long sessionsSold, long sessionsUsed) {
        return sessionsSold == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(sessionsUsed * 100).divide(BigDecimal.valueOf(sessionsSold), 1, RoundingMode.HALF_UP);
    }

    private static final class Agg {
        String name;
        long units, desk, sold, used, matureUnits, matureSold, matureUsed, lowUnits;
        BigDecimal value = BigDecimal.ZERO;

        boolean low() {
            return lowRedemption(matureSold, matureUsed);
        }
    }

    private static List<Agg> byPackage(List<Sold> sold, Instant matureBefore) {
        Map<UUID, Agg> m = new LinkedHashMap<>();
        for (Sold s : sold) {
            Agg a = m.computeIfAbsent(s.packageId(), k -> new Agg());
            a.name = s.packageName(); // rows come oldest first: a renamed package shows its newest name
            a.units++;
            if (s.atDesk()) a.desk++;
            a.value = a.value.add(s.price());
            a.sold += s.sessionsSold();
            a.used += s.sessionsUsed();
            if (matureBefore != null && s.soldAt().isBefore(matureBefore)) {
                a.matureUnits++;
                a.matureSold += s.sessionsSold();
                a.matureUsed += s.sessionsUsed();
                if (lowRedemption(s.sessionsSold(), s.sessionsUsed())) a.lowUnits++;
            }
        }
        return new ArrayList<>(m.values());
    }

    static Part sales(List<Sold> sold) {
        List<Agg> all = byPackage(sold, null);
        all.sort(Comparator.comparingLong((Agg a) -> -a.units).thenComparing(a -> a.name));
        long units = all.stream().mapToLong(a -> a.units).sum();
        long desk = all.stream().mapToLong(a -> a.desk).sum();
        BigDecimal value = all.stream().map(a -> a.value).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Part(List.of(new Kpi("pkgUnits", "Packages sold", units, "count"), new Kpi("pkgValue", "Sales value", value, "money"),
                new Kpi("pkgDesk", "Sold at the desk", desk, "count"), new Kpi("pkgWhatsapp", "Sold on WhatsApp", units - desk, "count")),
                barChart("Packages sold per package", all, a -> a.name, a -> BigDecimal.valueOf(a.units)),
                cols("package", "Package", "text", "units", "Packages sold", "number", "salesValue", "Sales value", "money",
                        "desk", "Desk", "number", "whatsapp", "WhatsApp", "number"),
                all.stream().map(a -> row("package", a.name, "units", a.units, "salesValue", a.value, "desk", a.desk, "whatsapp", a.units - a.desk)).toList(), null);
    }

    static Part redemption(List<Sold> sold, Instant matureBefore) {
        List<Agg> all = byPackage(sold, matureBefore);
        all.sort(Comparator.comparing((Agg a) -> !a.low()).thenComparing(a -> rate(a.sold, a.used)).thenComparing(a -> a.name));
        long sessSold = all.stream().mapToLong(a -> a.sold).sum();
        long sessUsed = all.stream().mapToLong(a -> a.used).sum();
        return new Part(List.of(new Kpi("sessSold", "Sessions sold", sessSold, "count"), new Kpi("sessUsed", "Sessions used", sessUsed, "count"),
                new Kpi("redemption", "Redemption rate", rate(sessSold, sessUsed), "percent"),
                new Kpi("lowPackages", "Packages with low redemption", all.stream().filter(Agg::low).count(), "count")),
                barChart("Redemption rate per package (%)", all, a -> a.name, a -> rate(a.sold, a.used)),
                cols("package", "Package", "text", "units", "Packages sold", "number", "sessSold", "Sessions sold", "number",
                        "sessUsed", "Sessions used", "number", "redemption", "Redemption %", "number", "matureUnits", "Sold 90+ days ago", "number",
                        "lowUnits", "Of those, under half used", "number", "flag", "Flag", "text"),
                all.stream().map(a -> row("package", a.name, "units", a.units, "sessSold", a.sold, "sessUsed", a.used, "redemption", rate(a.sold, a.used),
                        "matureUnits", a.matureUnits, "lowUnits", a.lowUnits, "flag", a.low() ? "low_redemption" : null)).toList(), null);
    }

    static Part expiry(List<Sold> lapsed, Map<UUID, String> names, Set<UUID> marketingConsented) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<UUID> patients = new HashSet<>(), reachable = new HashSet<>();
        long lost = 0;
        BigDecimal value = BigDecimal.ZERO;
        for (Sold s : lapsed) {
            long n = s.sessionsSold() - s.sessionsUsed();
            BigDecimal v = s.unusedValue().setScale(2, RoundingMode.HALF_UP);
            boolean ok = marketingConsented.contains(s.patientId());
            patients.add(s.patientId());
            if (ok) reachable.add(s.patientId());
            lost += n;
            value = value.add(v);
            rows.add(row("patient", names.getOrDefault(s.patientId(), ""), "package", s.packageName(), "lapsedOn", s.lastUsableDay(), "lost", n,
                    "lostValue", v, "winBack", ok ? "marketing_consent" : "no_marketing_consent"));
        }
        String note = rows.isEmpty() ? null : reachable.size() + " of " + patients.size()
                + " patients have marketing consent and may be sent a win-back message. Sending it from this screen is not available yet.";
        return new Part(List.of(new Kpi("lapsedSessions", "Sessions lapsed", lost, "count"), new Kpi("lapsedValue", "Value lapsed", value, "money"),
                new Kpi("lapsedPatients", "Patients who lost sessions", (long) patients.size(), "count"),
                new Kpi("winBackReach", "Of those, with marketing consent", (long) reachable.size(), "count")), null,
                cols("patient", "Patient", "text", "package", "Package", "text", "lapsedOn", "Lapsed on", "date", "lost", "Sessions lost", "number",
                        "lostValue", "Value lost", "money", "winBack", "Win-back", "text"), rows, note);
    }
}
