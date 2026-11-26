package com.nabd.hms.reports;

import com.nabd.hms.packages.api.PackageLedgerQueries.Sold;
import com.nabd.hms.reports.PackageReports.Part;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADM-049 unit: the low-redemption rule and the aggregation behind the three package reports (no database). */
class Adm049PackageReportsTest {

    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");
    private static final Instant MATURE_BEFORE = NOW.minus(90, ChronoUnit.DAYS);
    private static final UUID LASER = UUID.randomUUID(), PEEL = UUID.randomUUID();

    private static Sold sold(UUID pkg, String name, int daysAgo, boolean desk, String price, long sessions, long used) {
        return new Sold(UUID.randomUUID(), pkg, name, UUID.randomUUID(), NOW.minus(daysAgo, ChronoUnit.DAYS), desk, new BigDecimal(price), sessions, used,
                LocalDate.of(2026, 10, 20), BigDecimal.ZERO);
    }

    private static Number kpi(Part p, String key) {
        return p.kpis().stream().filter(k -> k.key().equals(key)).findFirst().orElseThrow().value();
    }

    private static Map<String, Object> rowOf(Part p, String pkg) {
        return p.rows().stream().filter(r -> pkg.equals(r.get("package"))).findFirst().orElseThrow();
    }

    @Test
    void lowRedemptionIsUnderHalfUsedAndNeverForNothingSold() {
        assertThat(PackageReports.lowRedemption(4, 1)).isTrue();
        assertThat(PackageReports.lowRedemption(4, 2)).as("exactly 50% is not below 50%").isFalse();
        assertThat(PackageReports.lowRedemption(5, 2)).as("40%").isTrue();
        assertThat(PackageReports.lowRedemption(5, 3)).isFalse();
        assertThat(PackageReports.lowRedemption(1, 0)).isTrue();
        assertThat(PackageReports.lowRedemption(0, 0)).isFalse();
        assertThat(PackageReports.rate(3, 1)).isEqualByComparingTo("33.3");
        assertThat(PackageReports.rate(0, 0)).isEqualByComparingTo("0");
    }

    @Test
    void aPackageIsFlaggedOnlyOnSalesAtLeast90DaysOld() {
        Part p = PackageReports.redemption(List.of(
                sold(LASER, "Laser", 100, true, "3200.00", 4, 1),  // old and 25% used
                sold(LASER, "Laser", 95, true, "3200.00", 4, 2),   // old and 50% used: together 3 of 8 = 37.5% -> low
                sold(LASER, "Laser", 5, true, "3200.00", 4, 4),    // new: lifts the shown rate but is not judged
                sold(PEEL, "Peel", 30, true, "1000.00", 4, 0)),    // 0% used but only 30 days old: not judged yet
                MATURE_BEFORE);
        assertThat(rowOf(p, "Laser")).containsEntry("flag", "low_redemption").containsEntry("matureUnits", 2L).containsEntry("lowUnits", 1L)
                .containsEntry("sessSold", 12L).containsEntry("sessUsed", 7L);
        assertThat((BigDecimal) rowOf(p, "Laser").get("redemption")).isEqualByComparingTo("58.3");
        assertThat(rowOf(p, "Peel")).containsEntry("flag", null).containsEntry("matureUnits", 0L);
        assertThat(p.rows().get(0).get("package")).as("flagged packages first").isEqualTo("Laser");
        assertThat(kpi(p, "lowPackages")).isEqualTo(1L);
        assertThat(kpi(p, "sessSold")).isEqualTo(16L);
        assertThat(kpi(p, "sessUsed")).isEqualTo(7L);
        assertThat((BigDecimal) kpi(p, "redemption")).isEqualByComparingTo("43.8");
        // the day a sale turns 90 days old it is judged
        assertThat(rowOf(PackageReports.redemption(List.of(new Sold(UUID.randomUUID(), PEEL, "Peel", UUID.randomUUID(), MATURE_BEFORE, true,
                BigDecimal.TEN, 4, 0, null, BigDecimal.ZERO)), MATURE_BEFORE), "Peel")).as("sold exactly at the boundary instant: not yet").containsEntry("flag", null);
        assertThat(rowOf(PackageReports.redemption(List.of(new Sold(UUID.randomUUID(), PEEL, "Peel", UUID.randomUUID(), MATURE_BEFORE.minusSeconds(1), true,
                BigDecimal.TEN, 4, 0, null, BigDecimal.ZERO)), MATURE_BEFORE), "Peel")).containsEntry("flag", "low_redemption");
    }

    @Test
    void salesSplitUnitsValueAndChannelPerPackageAndTheTotalsAreTheRowSums() {
        Part p = PackageReports.sales(List.of(sold(LASER, "Laser", 3, true, "3200.00", 4, 0), sold(LASER, "Laser", 2, false, "3200.00", 4, 0),
                sold(PEEL, "Peel", 1, true, "999.50", 2, 0)));
        assertThat(rowOf(p, "Laser")).containsEntry("units", 2L).containsEntry("desk", 1L).containsEntry("whatsapp", 1L);
        assertThat((BigDecimal) rowOf(p, "Laser").get("salesValue")).isEqualByComparingTo("6400.00");
        assertThat(rowOf(p, "Peel")).containsEntry("units", 1L).containsEntry("desk", 1L).containsEntry("whatsapp", 0L);
        assertThat(kpi(p, "pkgUnits")).isEqualTo(3L);
        assertThat(kpi(p, "pkgDesk")).isEqualTo(2L);
        assertThat(kpi(p, "pkgWhatsapp")).isEqualTo(1L);
        assertThat((BigDecimal) kpi(p, "pkgValue")).isEqualByComparingTo("7399.50");
        assertThat(PackageReports.sales(List.of()).rows()).isEmpty();
        assertThat(PackageReports.sales(List.of()).chart()).isNull();
    }

    @Test
    void expiryLossListsEachLapsedPackageAndCountsOnlyConsentedPatientsAsReachable() {
        UUID asha = UUID.randomUUID(), ravi = UUID.randomUUID();
        Sold a = new Sold(UUID.randomUUID(), LASER, "Laser", asha, NOW, true, new BigDecimal("2400.00"), 3, 0, LocalDate.of(2026, 10, 20), new BigDecimal("2400.0000"));
        Sold b = new Sold(UUID.randomUUID(), PEEL, "Peel", asha, NOW, true, new BigDecimal("1000.00"), 3, 2, LocalDate.of(2026, 10, 21), new BigDecimal("333.3333"));
        Sold c = new Sold(UUID.randomUUID(), LASER, "Laser", ravi, NOW, true, new BigDecimal("2400.00"), 3, 1, LocalDate.of(2026, 10, 22), new BigDecimal("1600.0000"));
        Part p = PackageReports.expiry(List.of(a, b, c), Map.of(asha, "Asha Rao", ravi, "Ravi Iyer"), Set.of(asha));
        assertThat(p.rows()).hasSize(3);
        assertThat(p.rows().get(0)).containsEntry("patient", "Asha Rao").containsEntry("package", "Laser").containsEntry("lost", 3L)
                .containsEntry("lapsedOn", LocalDate.of(2026, 10, 20)).containsEntry("winBack", "marketing_consent");
        assertThat(p.rows().get(2)).containsEntry("patient", "Ravi Iyer").containsEntry("lost", 2L).containsEntry("winBack", "no_marketing_consent");
        assertThat(kpi(p, "lapsedSessions")).isEqualTo(6L);
        assertThat((BigDecimal) kpi(p, "lapsedValue")).as("the sum of the rounded row values").isEqualByComparingTo("4333.33");
        assertThat(kpi(p, "lapsedPatients")).isEqualTo(2L);
        assertThat(kpi(p, "winBackReach")).as("a patient with two lapsed packages is one person to contact").isEqualTo(1L);
        assertThat(p.scopeNote()).startsWith("1 of 2 patients have marketing consent");
        assertThat(PackageReports.expiry(List.of(), Map.of(), Set.of()).scopeNote()).isNull();
    }
}
