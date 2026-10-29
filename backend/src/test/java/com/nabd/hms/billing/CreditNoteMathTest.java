package com.nabd.hms.billing;

import com.nabd.hms.billing.CreditNoteMath.Remaining;
import com.nabd.hms.billing.tax.TaxEngine.Component;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FIN-013: the pure credit-note arithmetic; the signed-off cases are in the FIN-010 golden pack. */
class CreditNoteMathTest {

    private static Component gst(String type, String rate, long taxable, long amount) {
        return new Component(type, new BigDecimal(rate), taxable, amount);
    }

    /** 3 sessions at 1,000.00 + 18%: taxable 3,000.00, CGST 270.00, SGST 270.00. */
    private static Remaining sessions() {
        return new Remaining("s", 3, 300_000, List.of(gst("CGST", "9.00", 300_000, 27_000), gst("SGST", "9.00", 300_000, 27_000)));
    }

    @Test
    void oneOfThreeUnitsTakesAThirdAndTheLastUnitTakesTheRest() {
        var first = CreditNoteMath.byQuantity(sessions(), 1);
        assertThat(first.taxableMinor()).isEqualTo(100_000);
        assertThat(first.taxMinor()).isEqualTo(18_000);
        var left = CreditNoteMath.after(sessions(), first);
        assertThat(left.quantity()).isEqualTo(2);
        var rest = CreditNoteMath.byQuantity(left, 2);
        assertThat(first.taxableMinor() + rest.taxableMinor()).isEqualTo(300_000);
        assertThat(first.taxMinor() + rest.taxMinor()).isEqualTo(54_000);
    }

    @Test
    void oddRemaindersNeverLoseOrInventAPaise() {
        Remaining r = new Remaining("x", 3, 1_000, List.of(gst("CGST", "9.00", 1_000, 91), gst("SGST", "9.00", 1_000, 91)));
        long taxable = 0, tax = 0;
        for (int i = 0; i < 3; i++) {
            var rev = CreditNoteMath.byQuantity(r, 1);
            taxable += rev.taxableMinor();
            tax += rev.taxMinor();
            r = CreditNoteMath.after(r, rev);
        }
        assertThat(taxable).isEqualTo(1_000);
        assertThat(tax).isEqualTo(182);
        assertThat(r.quantity()).isZero();
        assertThat(r.taxableMinor()).isZero();
    }

    @Test
    void quantityOutsideWhatIsLeftIsRefused() {
        assertThatThrownBy(() -> CreditNoteMath.byQuantity(sessions(), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CreditNoteMath.byQuantity(sessions(), 4)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void amountAboveTheRemainingValueOrBelowOneIsRefused() {
        Remaining r = new Remaining("a", 1, 100_000, List.of(gst("CGST", "9.00", 100_000, 9_000), gst("SGST", "9.00", 100_000, 9_000)));
        assertThatThrownBy(() -> CreditNoteMath.byAmount(List.of(r), 118_001, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CreditNoteMath.byAmount(List.of(r), 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(CreditNoteMath.byAmount(List.of(r), 118_000, 0).totalMinor()).isEqualTo(118_000);
    }

    @Test
    void theRoundOffGoesBackOnlyWithTheNoteThatClearsTheInvoice() {
        Remaining r = new Remaining("a", 1, 33_333, List.of(gst("CGST", "9.00", 33_333, 3_000), gst("SGST", "9.00", 33_333, 3_000)));
        var all = CreditNoteMath.byAmount(List.of(r), 39_300, -33); // gross 39,333 + round-off -33
        assertThat(all.roundOffMinor()).isEqualTo(-33);
        assertThat(all.totalMinor()).isEqualTo(39_300);
        var part = CreditNoteMath.byAmount(List.of(r), 10_000, -33);
        assertThat(part.roundOffMinor()).isZero();
        var byQty = CreditNoteMath.ofQuantities(List.of(r), List.of(CreditNoteMath.byQuantity(r, 1)), -33);
        assertThat(byQty.totalMinor()).isEqualTo(39_300);
    }

    @Test
    void exemptLineHasNoTaxPartWhenSplitByAmount() {
        Remaining ex = new Remaining("e", 1, 50_000, List.of());
        var n = CreditNoteMath.byAmount(List.of(ex), 20_000, 0);
        assertThat(n.taxMinor()).isZero();
        assertThat(n.taxableMinor()).isEqualTo(20_000);
    }
}
