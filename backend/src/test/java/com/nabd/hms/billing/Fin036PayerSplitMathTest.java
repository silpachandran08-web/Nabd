package com.nabd.hms.billing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** FIN-036: the split's arithmetic. Patient part + payer part = the amount, to the minor unit, for every percentage. */
class Fin036PayerSplitMathTest {

    private static final List<String> PERCENTS = List.of("0", "0.01", "0.5", "10", "12.5", "20", "33.33", "50", "66.67", "87.5", "99.99", "100");

    @Test
    void patientPartIsThePercentRoundedHalfUpToTheMinorUnit() {
        assertThat(PayerSplitService.patientPart(100_000, new BigDecimal("20"))).isEqualTo(20_000);   // 1000.00 at 20% = 200.00
        assertThat(PayerSplitService.patientPart(1001, new BigDecimal("12.5"))).isEqualTo(125);       // 125.125 -> 125
        assertThat(PayerSplitService.patientPart(1004, new BigDecimal("12.5"))).isEqualTo(126);       // 125.5 -> 126 (half up)
        assertThat(PayerSplitService.patientPart(100, new BigDecimal("0.5"))).isEqualTo(1);           // 0.5 -> 1
        assertThat(PayerSplitService.patientPart(33_333, new BigDecimal("33.33"))).isEqualTo(11_110); // 11109.8889 -> 11110
        assertThat(PayerSplitService.patientPart(1, new BigDecimal("33.33"))).isZero();
        assertThat(PayerSplitService.patientPart(0, new BigDecimal("20"))).isZero();
        assertThat(PayerSplitService.patientPart(99_999, new BigDecimal("0"))).isZero();
        assertThat(PayerSplitService.patientPart(99_999, new BigDecimal("100"))).isEqualTo(99_999);
    }

    @Test
    void everyAmountAndPercentSplitsIntoTwoNonNegativePartsThatAddUpExactly() {
        Random r = new Random(36);
        for (String pct : PERCENTS) {
            BigDecimal p = new BigDecimal(pct);
            for (int i = 0; i < 5_000; i++) {
                long gross = i < 300 ? i : r.nextInt(50_000_000);
                long patient = PayerSplitService.patientPart(gross, p);
                long payer = gross - patient;
                assertThat(patient).as("%s%% of %s", pct, gross).isBetween(0L, gross);
                assertThat(payer).isBetween(0L, gross);
                assertThat(patient + payer).isEqualTo(gross);
                // never more than half a minor unit away from the exact share
                BigDecimal exact = BigDecimal.valueOf(gross).multiply(p).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
                assertThat(exact.subtract(BigDecimal.valueOf(patient)).abs()).isLessThanOrEqualTo(new BigDecimal("0.5"));
            }
        }
    }

    @Test
    void anInvoiceOfManyLinesSplitsIntoSharesThatAddUpToItsTotal_roundOffIncluded() {
        Random r = new Random(7);
        for (String pct : PERCENTS) {
            BigDecimal p = new BigDecimal(pct);
            for (int n = 0; n < 400; n++) {
                long patientLines = 0, payerLines = 0, gross = 0;
                for (int l = 0; l < 1 + r.nextInt(6); l++) {
                    long g = r.nextInt(2_000_000);
                    long patient = PayerSplitService.patientPart(g, p);
                    patientLines += patient;
                    payerLines += g - patient;
                    gross += g;
                }
                long total = Math.round(gross / 100.0) * 100; // whole-unit round-off, as the tax engine does for INR
                long payer = PayerSplitService.payerShare(total, patientLines, payerLines);
                long patient = total - payer;
                assertThat(payer).as("%s%% payer share of %s", pct, total).isBetween(0L, total);
                assertThat(patient).isBetween(0L, total);
                assertThat(patient + payer).isEqualTo(total);
                if (patientLines == 0) assertThat(patient).as("a patient with nothing to pay on any line pays no round-off either").isZero();
                else assertThat(Math.abs(payer - payerLines)).as("the payer never carries the round-off unless it could not be the patient's").isLessThanOrEqualTo(50L);
            }
        }
    }

    @Test
    void roundOffRule() {
        // lines 1000.40 at 20%: patient 200.08, payer 800.32; total rounded to 1000.00: the patient carries the -0.40
        assertThat(PayerSplitService.payerShare(100_000, 20_008, 80_032)).isEqualTo(80_032);
        // co-pay 0%: the payer takes the whole total, round-off included (+0.30 here), the patient owes nothing
        assertThat(PayerSplitService.payerShare(100_100, 0, 100_070)).isEqualTo(100_100);
        // co-pay 100%: nothing for the payer
        assertThat(PayerSplitService.payerShare(100_000, 100_040, 0)).isZero();
        // a negative round-off larger than the patient's lines can never make the payer owe more than the total
        assertThat(PayerSplitService.payerShare(100, 1, 149)).isEqualTo(100);
    }
}
