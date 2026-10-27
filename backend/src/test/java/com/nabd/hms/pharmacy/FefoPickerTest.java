package com.nabd.hms.pharmacy;

import com.nabd.hms.pharmacy.FefoPicker.Batch;
import com.nabd.hms.pharmacy.api.StockCommands.Pick;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PHL-006 unit: FEFO split, mid-course rule, expired block, determinism and a property test. */
class FefoPickerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);

    private static Batch b(String id, LocalDate exp, int qty) {
        return new Batch(new UUID(0, Long.parseLong(id)), "B" + id, exp, null, null, qty, "active", null);
    }

    @Test
    void eightUnitsTakeFiveFromTheEarlierBatchAndThreeFromTheNext() {
        var plan = FefoPicker.plan(List.of(b("2", TODAY.plusMonths(8), 50), b("1", TODAY.plusMonths(2), 5)), 8, null, TODAY);
        assertThat(plan.picks()).extracting(Pick::qty).containsExactly(5, 3);
        assertThat(plan.picks().get(0).batchId()).isEqualTo(new UUID(0, 1));
    }

    @Test
    void expiredEmptyAndQuarantinedBatchesAreNeverPickedAndExpiryTodayIsStillUsable() {
        Batch quarantined = new Batch(new UUID(0, 9), "Q", TODAY.plusDays(1), null, null, 99, "quarantined", null);
        var plan = FefoPicker.plan(List.of(b("1", TODAY.minusDays(1), 99), b("2", TODAY, 4), b("3", TODAY.plusDays(5), 0), quarantined), 4, null, TODAY);
        assertThat(plan.picks()).singleElement().satisfies(p -> assertThat(p.batchId()).isEqualTo(new UUID(0, 2)));
        var short1 = FefoPicker.plan(List.of(b("1", TODAY.minusDays(1), 99)), 1, null, TODAY);
        assertThat(short1.enough()).isFalse();
        assertThat(short1.sellable()).isZero();
    }

    @Test
    void unknownExpiryIsLastAndTiesGoByBatchId() {
        var plan = FefoPicker.plan(List.of(b("5", null, 10), b("4", TODAY.plusDays(9), 1), b("3", TODAY.plusDays(9), 1)), 3, null, TODAY);
        assertThat(plan.picks()).extracting(Pick::batchId).containsExactly(new UUID(0, 3), new UUID(0, 4), new UUID(0, 5));
    }

    @Test
    void aBatchExpiringBeforeTheCourseEndsIsSkippedWhenOtherStockCoversIt() {
        var plan = FefoPicker.plan(List.of(b("1", TODAY.plusDays(20), 10), b("2", TODAY.plusDays(200), 50)), 5, 30, TODAY);
        assertThat(plan.picks()).singleElement().satisfies(p -> {
            assertThat(p.batchId()).isEqualTo(new UUID(0, 2));
            assertThat(p.warning()).isNull();
        });
    }

    @Test
    void whenNothingElseCoversItTheMidCourseBatchIsUsedWithAWarning() {
        var plan = FefoPicker.plan(List.of(b("1", TODAY.plusDays(20), 10), b("2", TODAY.plusDays(200), 50)), 55, 30, TODAY);
        assertThat(plan.picks()).extracting(Pick::warning).containsExactly("expires-mid-course", null);
        assertThat(plan.picks()).extracting(Pick::qty).containsExactly(10, 45);
    }

    @Test
    void aBatchExpiringExactlyOnTheLastCourseDayHasNoWarning() {
        var plan = FefoPicker.plan(List.of(b("1", TODAY.plusDays(30), 10)), 5, 30, TODAY);
        assertThat(plan.picks()).singleElement().satisfies(p -> assertThat(p.warning()).isNull());
    }

    @Test
    void property_neverPicksExpiredNeverNegativeAlwaysSumsToTheRequestAndIsDeterministic() {
        Random rnd = new Random(42);
        for (int round = 0; round < 2000; round++) {
            List<Batch> all = new ArrayList<>();
            for (int i = 0, n = rnd.nextInt(6); i < n; i++) {
                LocalDate exp = rnd.nextInt(5) == 0 ? null : TODAY.plusDays(rnd.nextInt(120) - 40);
                all.add(new Batch(new UUID(0, i), "B", exp, null, null, rnd.nextInt(30), rnd.nextInt(6) == 0 ? "quarantined" : "active", null));
            }
            int qty = 1 + rnd.nextInt(60);
            Integer course = rnd.nextBoolean() ? null : 1 + rnd.nextInt(90);
            var plan = FefoPicker.plan(all, qty, course, TODAY);
            assertThat(FefoPicker.plan(all, qty, course, TODAY)).isEqualTo(plan);
            if (!plan.enough()) {
                assertThat(plan.sellable()).isLessThan(qty);
                continue;
            }
            assertThat(plan.picks().stream().mapToInt(Pick::qty).sum()).isEqualTo(qty);
            for (Pick p : plan.picks()) {
                assertThat(p.qty()).isPositive();
                assertThat(p.expiryDate() == null || !p.expiryDate().isBefore(TODAY)).isTrue();
                Batch src = all.stream().filter(x -> x.id().equals(p.batchId()) && x.sellable(TODAY)).findFirst().orElseThrow();
                assertThat(p.qty()).isLessThanOrEqualTo(src.qtyOnHand());
            }
        }
    }
}
