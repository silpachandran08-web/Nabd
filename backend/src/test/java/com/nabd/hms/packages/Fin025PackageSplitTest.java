package com.nabd.hms.packages;

import com.nabd.hms.packages.PackageSplit.Sold;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIN-025 arithmetic, no Spring and no database: the pro-rata split (its WE-01 / WE-02 figures are the carts of the FIN-010 golden
 * cases IN-047 and PHL-SA-025, which GoldenPackTest runs through the tax engine) and the per-line refund after partial use.
 */
class Fin025PackageSplitTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> golden(String dir, String id) throws IOException {
        Map<String, Object> file = JsonMapper.builder().build().readValue(Files.readString(Path.of("src/test/resources/golden", dir, "cases.json")), Map.class);
        return ((List<Map<String, Object>>) file.get("cases")).stream().filter(c -> id.equals(c.get("id"))).findFirst().orElseThrow();
    }

    /** The golden case's cart lines are exactly what the allocation gives for its bundle price and list values. */
    @SuppressWarnings("unchecked")
    private static void assertAllocationIsTheGoldenCart(String dir, String id) throws IOException {
        Map<String, Object> c = golden(dir, id);
        Map<String, Object> pkg = (Map<String, Object>) c.get("package");
        long bundle = ((Number) pkg.get("bundleMinor")).longValue();
        long[] list = ((List<Number>) pkg.get("listValueMinor")).stream().mapToLong(Number::longValue).toArray();
        long[] cart = ((List<Map<String, Object>>) ((Map<String, Object>) c.get("cart")).get("lines")).stream()
                .mapToLong(l -> ((Number) l.get("grossMinor")).longValue()).toArray();
        long[] got = PackageSplit.allocate(bundle, list);
        assertThat(got).as(id + " allocation").containsExactly(cart);
        assertThat(java.util.Arrays.stream(got).sum()).as(id + " shares add up to the bundle price").isEqualTo(bundle);
    }

    @Test
    void we01AcneClearIsSplitProRataAndMatchesTheGoldenCart() throws IOException {
        assertThat(PackageSplit.allocate(1_299_900, new long[]{1_200_000, 300_000, 80_000})).containsExactly(987_266, 246_816, 65_818);
        assertAllocationIsTheGoldenCart("in", "IN-047");
    }

    @Test
    void we02InclusiveBundleMatchesTheGoldenCart() throws IOException {
        assertAllocationIsTheGoldenCart("phl-sa", "PHL-SA-025");
    }

    @Test
    void theRoundingRemainderLandsOnTheLastLineAndTheSharesAlwaysAddUp() {
        // 100.00 over three equal components: 33.33 + 33.33, the last takes 33.34
        assertThat(PackageSplit.allocate(10_000, new long[]{1, 1, 1})).containsExactly(3_333, 3_333, 3_334);
        // 0.02 over three: half-up gives 1 + 1, the last takes the (zero) rest
        assertThat(PackageSplit.allocate(2, new long[]{1, 1, 1})).containsExactly(1, 1, 0);
        // a free add-on at the end never takes the remainder: the last component with a list value does
        assertThat(PackageSplit.allocate(10_000, new long[]{1, 2, 0})).containsExactly(3_333, 6_667, 0);
        assertThat(PackageSplit.allocate(10_000, new long[]{5_000})).containsExactly(10_000);
        for (long bundle = 1; bundle < 3_000; bundle += 7) {
            assertThat(java.util.Arrays.stream(PackageSplit.allocate(bundle, new long[]{1_200_000, 300_000, 80_000})).sum()).isEqualTo(bundle);
        }
    }

    // WE-01 as invoiced (golden IN-047): sessions exempt, peels 18% (444.26), cream 18% (118.48)
    private static List<Sold> we01(int sessionsUsed, int peelsUsed, int creamUsed) {
        return List.of(new Sold("Acne session", sessionsUsed, 200_000, 987_266, 0), new Sold("Peel", peelsUsed, 150_000, 246_816, 44_426),
                new Sold("Cream", creamUsed, 80_000, 65_818, 11_848));
    }

    @Test
    void refundAfterThreeOfSixSessionsValuesThemAtFullPriceAndReversesTaxLineByLineWithNoFee() {
        var r = PackageSplit.refund(false, we01(3, 0, 0));
        assertThat(r.lines().get(0)).isEqualTo(new PackageSplit.RefundLine("Acne session", 3, 600_000, 387_266, 0)); // exempt: no tax to reverse
        assertThat(r.lines().get(1)).isEqualTo(new PackageSplit.RefundLine("Peel", 0, 0, 246_816, 44_426));          // untouched: all of its tax
        assertThat(r.lines().get(2)).isEqualTo(new PackageSplit.RefundLine("Cream", 0, 0, 65_818, 11_848));
        assertThat(r.paidMinor()).isEqualTo(1_356_174);
        assertThat(r.usedMinor()).isEqualTo(600_000);
        assertThat(r.refundMinor()).as("paid minus three sessions at 2,000 each, nothing else deducted").isEqualTo(1_356_174 - 600_000);
        assertThat(r.owedMinor()).isZero();
    }

    @Test
    void aPartlyUsedTaxableLineKeepsTheTaxOnTheUsedValue() {
        var r = PackageSplit.refund(false, we01(0, 1, 0));
        // one peel at the full 1,500: 968.16 of the line comes back with its share of the 444.26
        assertThat(r.lines().get(1)).isEqualTo(new PackageSplit.RefundLine("Peel", 1, 150_000, 96_816, 17_427));
        assertThat(r.refundMinor()).isEqualTo(987_266 + 96_816 + 17_427 + 65_818 + 11_848);
    }

    @Test
    void aLineUsedBeyondItsShareTakesTheExcessOffTheOtherLinesAndNoLineGoesNegative() {
        var r = PackageSplit.refund(false, we01(5, 0, 0)); // 10,000 used against a 9,872.66 share: 127.34 comes off peel and cream
        assertThat(r.lines().get(0).refundTaxableMinor()).isZero();
        assertThat(r.lines().get(1).refundTaxableMinor()).isEqualTo(236_763);
        assertThat(r.lines().get(2).refundTaxableMinor()).isEqualTo(63_137);
        assertThat(r.lines().stream().mapToLong(PackageSplit.RefundLine::refundTaxableMinor).sum()).isEqualTo(1_299_900 - 1_000_000);
        assertThat(r.lines()).allSatisfy(l -> assertThat(l.refundTaxMinor()).isBetween(0L, 44_426L));
        assertThat(r.owedMinor()).isZero();
    }

    @Test
    void everythingUsedRefundsNothingAndShowsWhatThePatientOwes() {
        var r = PackageSplit.refund(false, we01(6, 2, 1));
        assertThat(r.refundMinor()).isZero();
        assertThat(r.owedMinor()).isEqualTo(1_580_000 - 1_299_900);
        assertThat(r.lines()).allSatisfy(l -> assertThat(l.refundTaxableMinor() + l.refundTaxMinor()).isZero());
    }

    @Test
    void anInclusiveBundleRefundsOutOfTheTaxInclusiveShare() {
        // WE-02 as invoiced (golden PHL-SA-025): shares 2,559.15 / 239.92 / 199.93 contain the VAT
        var r = PackageSplit.refund(true, List.of(new Sold("Laser session", 2, 80_000, 222_535, 33_380), new Sold("Consultation", 0, 30_000, 23_992, 0),
                new Sold("Serum", 0, 25_000, 17_385, 2_608)));
        assertThat(r.lines().get(0)).isEqualTo(new PackageSplit.RefundLine("Laser session", 2, 160_000, 83_404, 12_511));
        assertThat(r.lines().get(2)).isEqualTo(new PackageSplit.RefundLine("Serum", 0, 0, 17_385, 2_608));
        assertThat(r.paidMinor()).isEqualTo(299_900);
        assertThat(r.refundMinor()).as("bundle price minus two sessions at the 800 list price").isEqualTo(299_900 - 160_000);
    }
}
