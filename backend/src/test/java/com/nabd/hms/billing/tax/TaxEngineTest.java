package com.nabd.hms.billing.tax;

import com.nabd.hms.billing.tax.TaxEngine.Cart;
import com.nabd.hms.billing.tax.TaxEngine.Category;
import com.nabd.hms.billing.tax.TaxEngine.Line;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** FIN-008 / FIN-009: pure engine tests, no Spring, no database. Money is in minor units (paise / halalas). */
class TaxEngineTest {

    private static final BigDecimal R18 = new BigDecimal("18.00"), R5 = new BigDecimal("5.00");
    private final TaxEngine gst = new RuleBasedTaxEngine(new IndiaGst());

    private static Line line(String key, long gross, Category c, BigDecimal rate) {
        return new Line(key, gross, c, rate, true, false, false);
    }

    @Test
    void qa13DiscountReducesTheTaxableValueBeforeTax() {
        var r = gst.compute(new Cart(2, true, false, 10_000, List.of(line("a", 100_000, Category.TAXABLE, R18))));
        assertThat(r.lines().get(0).taxableMinor()).isEqualTo(90_000);
        assertThat(r.taxMinor()).isEqualTo(16_200);
        assertThat(r.lines().get(0).taxes()).extracting(TaxEngine.Component::type).containsExactly("CGST", "SGST");
        assertThat(r.lines().get(0).taxes()).extracting(TaxEngine.Component::amountMinor).containsExactly(8_100L, 8_100L);
        assertThat(r.totalMinor()).isEqualTo(106_200);
    }

    @Test
    void discountIsAllocatedProRataAndSumsExactly() {
        var r = gst.compute(new Cart(2, true, false, 17_000, List.of(
                line("ex", 50_000, Category.EXEMPT, BigDecimal.ZERO), line("g18", 100_000, Category.TAXABLE, R18), line("g5", 20_000, Category.TAXABLE, R5))));
        assertThat(r.lines()).extracting(TaxEngine.LineResult::discountMinor).containsExactly(5_000L, 10_000L, 2_000L);
        assertThat(r.lines().get(0).taxMinor()).isZero();
        assertThat(r.lines().get(1).taxMinor()).isEqualTo(16_200); // 18% of 900.00
        assertThat(r.lines().get(2).taxMinor()).isEqualTo(900);    // 5% of 180.00
    }

    @Test
    void largestRemainderKeepsTheSumExactAndBreaksTiesToTheEarlierLine() {
        assertThat(RuleBasedTaxEngine.allocate(100, new long[]{1, 1, 1})).containsExactly(34, 33, 33);
        assertThat(RuleBasedTaxEngine.allocate(0, new long[]{5, 5})).containsExactly(0, 0);
        assertThat(RuleBasedTaxEngine.allocate(7, new long[]{0, 3, 0})).containsExactly(0, 7, 0);
    }

    @Test
    void gccCurrencyKeepsTwoDecimalsAndNoRoundOffLine() {
        var vat = new RuleBasedTaxEngine(new FlatVat());
        var r = vat.compute(new Cart(2, false, false, 0, List.of(line("a", 10_055, Category.TAXABLE, new BigDecimal("15.00")))));
        assertThat(r.lines().get(0).taxes().get(0).type()).isEqualTo("VAT");
        assertThat(r.taxMinor()).isEqualTo(1_508); // 15.0825 -> 15.08
        assertThat(r.roundOffMinor()).isZero();
        assertThat(r.totalMinor()).isEqualTo(11_563);
    }

    @Test
    void inrTotalRoundsToTheRupeeWithARoundOff() {
        var r = gst.compute(new Cart(2, true, false, 0, List.of(line("a", 33_333, Category.NIL, BigDecimal.ZERO))));
        assertThat(r.totalMinor()).isEqualTo(33_300);
        assertThat(r.roundOffMinor()).isEqualTo(-33);
        var up = gst.compute(new Cart(2, true, false, 0, List.of(line("a", 33_350, Category.NIL, BigDecimal.ZERO))));
        assertThat(up.totalMinor()).isEqualTo(33_400); // half-up
    }

    @Test
    void interStateSupplyIsIgstAndBillOfSupplyHasNoTaxLines() {
        var r = gst.compute(new Cart(2, true, false, 0, List.of(new Line("m", 10_000, Category.TAXABLE, R5, true, false, true))));
        assertThat(r.lines().get(0).taxes()).hasSize(1);
        assertThat(r.lines().get(0).taxes().get(0).type()).isEqualTo("IGST");
        assertThat(r.lines().get(0).taxes().get(0).amountMinor()).isEqualTo(500);
        var bos = gst.compute(new Cart(2, true, true, 1_000, List.of(line("a", 100_000, Category.TAXABLE, R18))));
        assertThat(bos.taxMinor()).isZero();
        assertThat(bos.lines().get(0).taxes()).isEmpty();
        assertThat(bos.totalMinor()).isEqualTo(99_000);
    }

    @Test
    void inclusivePriceBacksTheTaxOut() {
        var r = gst.compute(new Cart(2, false, false, 0, List.of(new Line("a", 11_800, Category.TAXABLE, R18, true, true, false))));
        assertThat(r.lines().get(0).taxableMinor()).isEqualTo(10_000);
        assertThat(r.taxMinor()).isEqualTo(1_800);
        assertThat(r.totalMinor()).isEqualTo(11_800);
    }

    @Test
    void discountAboveTheDiscountableValueIsRefused() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> gst.compute(new Cart(2, true, false, 100_001, List.of(line("a", 100_000, Category.NIL, BigDecimal.ZERO)))));
    }

    /** Property: for random carts the allocation sums to the discount, every line adds up and the engine is deterministic. */
    @Test
    void invariantsHoldForRandomCarts() {
        Random rnd = new Random(8);
        BigDecimal[] rates = {BigDecimal.ZERO, R5, new BigDecimal("12.00"), R18};
        for (int n = 0; n < 3000; n++) {
            boolean inr = rnd.nextBoolean();
            List<Line> lines = new ArrayList<>();
            long gross = 0;
            for (int i = 0, k = 1 + rnd.nextInt(8); i < k; i++) {
                long g = rnd.nextInt(5_000_000);
                BigDecimal rate = rates[rnd.nextInt(rates.length)];
                gross += g;
                lines.add(new Line("l" + i, g, rate.signum() == 0 ? Category.EXEMPT : Category.TAXABLE, rate, true, rnd.nextInt(5) == 0, rnd.nextBoolean()));
            }
            long discount = gross == 0 ? 0 : (long) (rnd.nextDouble() * gross);
            var cart = new Cart(2, inr, rnd.nextInt(10) == 0, discount, lines);
            var r = gst.compute(cart);
            assertThat(r.lines().stream().mapToLong(TaxEngine.LineResult::discountMinor).sum()).isEqualTo(discount);
            long sum = r.lines().stream().mapToLong(l -> l.taxableMinor() + l.taxMinor()).sum();
            assertThat(sum + r.roundOffMinor()).isEqualTo(r.totalMinor());
            assertThat(r.subtotalMinor() - r.discountMinor() + r.taxMinor() + r.roundOffMinor()).isEqualTo(r.totalMinor());
            if (inr) assertThat(r.totalMinor() % 100).isZero(); else assertThat(r.roundOffMinor()).isZero();
            assertThat(gst.compute(cart)).isEqualTo(r);
        }
    }

    @Test
    void placeOfSupplyDecisionTable() {
        String ka = "29ABCDE1234F1Z5", mh = "27ABCDE1234F1Z5", ka2 = "29XYZAB9876C1Z2";
        assertThat(PlaceOfSupply.interState(ka, mh, true)).isTrue();    // goods, B2B, other state
        assertThat(PlaceOfSupply.interState(ka, ka2, true)).isFalse();  // goods, B2B, same state
        assertThat(PlaceOfSupply.interState(ka, mh, false)).isFalse();  // services: clinic's state
        assertThat(PlaceOfSupply.interState(ka, null, true)).isFalse(); // consumer sale
        assertThat(PlaceOfSupply.interState(null, mh, true)).isFalse(); // clinic state unknown
        assertThat(PlaceOfSupply.interState(ka, "not-a-gstin", true)).isFalse();
        assertThat(PlaceOfSupply.validGstin(ka)).isTrue();
        assertThat(PlaceOfSupply.validGstin("29ABCDE1234F1Z")).isFalse();
    }
}
