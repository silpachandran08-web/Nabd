package com.nabd.hms.packages;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** S25 QA t1 (FIN-025): the bundle split adds up to the bundle price to the minor unit, whatever the list values and price. */
class S25QaFin025AllocationTest {

    @Test
    void sharesAlwaysAddUpToTheBundlePrice_andNoneIsNegative_forRealisticBundles() {
        Random rnd = new Random(25);
        for (int round = 0; round < 20_000; round++) {
            int n = 1 + rnd.nextInt(8);
            long[] list = new long[n];
            for (int i = 0; i < n; i++) list[i] = rnd.nextInt(6) == 0 ? 0 : 100 + rnd.nextInt(3_000_000);
            if (Arrays.stream(list).sum() == 0) list[n - 1] = 5_000;
            long bundle = 1_000 + rnd.nextInt(50_000_000);
            long[] got = PackageSplit.allocate(bundle, list);
            assertThat(Arrays.stream(got).sum()).as("bundle %d over %s", bundle, Arrays.toString(list)).isEqualTo(bundle);
            assertThat(Arrays.stream(got).min().getAsLong()).as("smallest share of bundle %d over %s", bundle, Arrays.toString(list)).isGreaterThanOrEqualTo(0);
            for (int i = 0; i < n; i++) if (list[i] == 0) assertThat(got[i]).as("a free add-on takes no share").isZero();
        }
    }

    @Test
    void aRefundNeverExceedsWhatWasPaid() {
        // used list value above the bundle price: the refund is clamped, not negative
        var sold = java.util.List.of(new PackageSplit.Sold("A", 6, 200_000, 987_266, 0), new PackageSplit.Sold("B", 2, 150_000, 246_816, 44_426));
        var r = PackageSplit.refund(false, sold);
        assertThat(r.refundMinor()).isBetween(0L, r.paidMinor());
        r.lines().forEach(l -> {
            assertThat(l.refundTaxableMinor()).isGreaterThanOrEqualTo(0);
            assertThat(l.refundTaxMinor()).isGreaterThanOrEqualTo(0);
        });
    }
}
