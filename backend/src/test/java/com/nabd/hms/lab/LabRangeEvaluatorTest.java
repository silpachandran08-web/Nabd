package com.nabd.hms.lab;

import com.nabd.hms.lab.LabRangeDtos.RangeBand;
import com.nabd.hms.lab.LabRangeEvaluator.Conversion;
import com.nabd.hms.lab.LabRangeEvaluator.Evaluation;
import com.nabd.hms.lab.LabRangeEvaluator.Flag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PHL-042: the flagging table. The haemoglobin and potassium numbers are the story's own acceptance examples; every other
 * limit here is TEST DATA chosen to hit a branch, not a clinical range.
 */
class LabRangeEvaluatorTest {

    private static final int YEAR = 365;

    private static BigDecimal n(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    private static RangeBand band(String sex, int min, Integer max, String low, String high, String critLow, String critHigh) {
        return new RangeBand(sex, min, max, n(low), n(high), n(critLow), n(critHigh), null);
    }

    /** Haemoglobin g/dL: child under 12 years 11.0-14.5 (the story's context), adult female 12.0-15.5 (AC1); adult male is TEST DATA. */
    private static final List<RangeBand> HB = List.of(
            band("any", 0, 12 * YEAR, "11.0", "14.5", null, null),
            band("F", 12 * YEAR, null, "12.0", "15.5", null, null),
            band("M", 12 * YEAR, null, "13.0", "17.0", null, null));

    private static Flag flag(List<RangeBand> bands, String sex, Integer ageDays, String value) {
        return LabRangeEvaluator.evaluate(bands, sex, ageDays, n(value)).flag();
    }

    @Test
    void ac1_aWomanOf30WithHaemoglobin11_2AgainstTwelveToFifteenPointFiveIsFlaggedLow() {
        Evaluation e = LabRangeEvaluator.evaluate(HB, "F", 30 * YEAR, n("11.2"));
        assertThat(e.flag()).isEqualTo(Flag.LOW);
        assertThat(e.flag().code()).isEqualTo("L");
        assertThat(e.band().low()).isEqualByComparingTo("12.0");
        assertThat(flag(HB, "F", 30 * YEAR, "12.0")).as("the low limit itself is normal").isEqualTo(Flag.NORMAL);
        assertThat(flag(HB, "F", 30 * YEAR, "15.5")).as("the high limit itself is normal").isEqualTo(Flag.NORMAL);
        assertThat(flag(HB, "F", 30 * YEAR, "15.6")).isEqualTo(Flag.HIGH);
        assertThat(Flag.HIGH.code()).isEqualTo("H");
        assertThat(flag(HB, "M", 30 * YEAR, "12.5")).as("the same value is low for a man").isEqualTo(Flag.LOW);
    }

    @Test
    void ac2_aSixYearOldIsFlaggedAgainstTheChildBand() {
        Evaluation e = LabRangeEvaluator.evaluate(HB, "F", 6 * YEAR, n("11.5"));
        assertThat(e.band().ageMaxDays()).as("the child band").isEqualTo(12 * YEAR);
        assertThat(e.flag()).as("11.5 is normal for a child, and would be low against the adult female band").isEqualTo(Flag.NORMAL);
        assertThat(flag(HB, "M", 6 * YEAR, "10.9")).isEqualTo(Flag.LOW);
        assertThat(flag(HB, null, 6 * YEAR, "12")).as("an 'any' band needs no sex").isEqualTo(Flag.NORMAL);
        // the band includes its first day and excludes its last
        assertThat(LabRangeEvaluator.resolve(HB, "F", 12 * YEAR - 1).orElseThrow().sex()).isEqualTo("any");
        assertThat(LabRangeEvaluator.resolve(HB, "F", 12 * YEAR).orElseThrow().sex()).isEqualTo("F");
    }

    @Test
    void ac3_potassium6_6WithCriticalHigh6_0IsCritical() {
        List<RangeBand> k = List.of(band("any", 0, null, "3.5", "5.1", "2.5", "6.0")); // 6.0 is the story's; the rest TEST DATA
        Evaluation e = LabRangeEvaluator.evaluate(k, "M", 50 * YEAR, n("6.6"));
        assertThat(e.flag()).isEqualTo(Flag.CRITICAL_HIGH);
        assertThat(e.flag().code()).isEqualTo("C");
        assertThat(e.flag().critical()).isTrue();
        assertThat(flag(k, "M", 50 * YEAR, "6.0")).as("the critical limit itself is critical").isEqualTo(Flag.CRITICAL_HIGH);
        assertThat(flag(k, "M", 50 * YEAR, "5.9")).isEqualTo(Flag.HIGH);
        assertThat(flag(k, "M", 50 * YEAR, "4.0")).isEqualTo(Flag.NORMAL);
        assertThat(flag(k, "M", 50 * YEAR, "3.0")).isEqualTo(Flag.LOW);
        assertThat(flag(k, "M", 50 * YEAR, "2.5")).isEqualTo(Flag.CRITICAL_LOW);
        assertThat(flag(k, "M", 50 * YEAR, "1.9")).isEqualTo(Flag.CRITICAL_LOW);
        assertThat(Flag.CRITICAL_LOW.code()).isEqualTo("C");
        assertThat(Flag.CRITICAL_LOW.critical()).isTrue();
        assertThat(Flag.HIGH.critical()).isFalse();
        assertThat(flag(k, null, null, "6.6")).as("a band for everyone needs neither sex nor age").isEqualTo(Flag.CRITICAL_HIGH);
    }

    @Test
    void ac5_noBandForThePatientIsNoReferenceRangeNeverNormal() {
        List<RangeBand> onlyWomen = List.of(band("F", 12 * YEAR, null, "12.0", "15.5", null, null));
        Evaluation man = LabRangeEvaluator.evaluate(onlyWomen, "M", 30 * YEAR, n("13.0"));
        assertThat(man.flag()).isEqualTo(Flag.NO_RANGE);
        assertThat(man.band()).isNull();
        assertThat(man.flag().code()).isEmpty();
        assertThat(flag(onlyWomen, "F", 6 * YEAR, "13.0")).as("a girl below the band's age").isEqualTo(Flag.NO_RANGE);
        assertThat(flag(onlyWomen, null, 30 * YEAR, "13.0")).as("sex not known").isEqualTo(Flag.NO_RANGE);
        assertThat(flag(onlyWomen, "F", null, "13.0")).as("age not known and the band is not for every age").isEqualTo(Flag.NO_RANGE);
        assertThat(flag(HB, "F", null, "13.0")).as("age not known: the child band starts at birth but ends").isEqualTo(Flag.NO_RANGE);
        assertThat(flag(List.of(), "F", 30 * YEAR, "13.0")).as("a test with no ranges").isEqualTo(Flag.NO_RANGE);
        assertThat(flag(HB, "F", -1, "13.0")).as("a sample dated before birth").isEqualTo(Flag.NO_RANGE);
    }

    @Test
    void neonatesGetTheirOwnDayBands() {
        List<RangeBand> bands = List.of( // TEST DATA
                band("any", 0, 3, "10", "20", "5", "30"),
                band("any", 3, 28, "8", "18", null, null),
                band("any", 28, YEAR, "6", "16", null, null));
        assertThat(LabRangeEvaluator.resolve(bands, "M", 0).orElseThrow().high()).as("day of birth").isEqualByComparingTo("20");
        assertThat(LabRangeEvaluator.resolve(bands, "M", 2).orElseThrow().high()).isEqualByComparingTo("20");
        assertThat(LabRangeEvaluator.resolve(bands, "F", 3).orElseThrow().high()).isEqualByComparingTo("18");
        assertThat(LabRangeEvaluator.resolve(bands, "F", 27).orElseThrow().high()).isEqualByComparingTo("18");
        assertThat(LabRangeEvaluator.resolve(bands, null, 28).orElseThrow().high()).isEqualByComparingTo("16");
        assertThat(flag(bands, "M", 1, "19")).isEqualTo(Flag.NORMAL);
        assertThat(flag(bands, "M", 5, "19")).as("the same value three days later").isEqualTo(Flag.HIGH);
        assertThat(flag(bands, "M", 1, "4")).isEqualTo(Flag.CRITICAL_LOW);
        assertThat(flag(bands, "M", YEAR, "10")).as("after the last band").isEqualTo(Flag.NO_RANGE);
    }

    @Test
    void aBandWithOneLimitOrNoneIsNeverAFalseNormal() {
        RangeBand upTo = band("any", 0, null, null, "200", null, null);
        assertThat(flag(List.of(upTo), "M", 1, "150")).isEqualTo(Flag.NORMAL);
        assertThat(flag(List.of(upTo), "M", 1, "201")).isEqualTo(Flag.HIGH);
        RangeBand from = band("any", 0, null, "40", null, null, null);
        assertThat(flag(List.of(from), "M", 1, "60")).isEqualTo(Flag.NORMAL);
        assertThat(flag(List.of(from), "M", 1, "39")).isEqualTo(Flag.LOW);
        RangeBand text = new RangeBand("any", 0, null, null, null, null, null, "Negative");
        Evaluation t = LabRangeEvaluator.evaluate(List.of(text), "M", 1, n("1"));
        assertThat(t.flag()).as("nothing to compare with").isEqualTo(Flag.NO_RANGE);
        assertThat(t.band().text()).as("the text range is still shown").isEqualTo("Negative");
        RangeBand criticalOnly = band("any", 0, null, null, null, "2", "9");
        assertThat(flag(List.of(criticalOnly), "M", 1, "5")).isEqualTo(Flag.NO_RANGE);
        assertThat(flag(List.of(criticalOnly), "M", 1, "9")).isEqualTo(Flag.CRITICAL_HIGH);
        assertThat(flag(List.of(criticalOnly), "M", 1, "2")).isEqualTo(Flag.CRITICAL_LOW);
    }

    @Test
    void unitConversionBringsAnEnteredValueToTheCanonicalUnit() {
        Conversion glucose = new Conversion(BigDecimal.ONE, n("18")); // mg/dL to mmol/L (the story: mmol/L x 18 = mg/dL)
        assertThat(glucose.apply(n("180"))).isEqualByComparingTo("10");
        assertThat(glucose.inverse().apply(n("5.5"))).as("mmol/L shown as mg/dL").isEqualByComparingTo("99");
        Conversion creatinine = new Conversion(n("88.4"), BigDecimal.ONE); // mg/dL to umol/L (the story: umol/L / 88.4)
        assertThat(creatinine.apply(n("1.2"))).isEqualByComparingTo("106.08");
        assertThat(creatinine.inverse().apply(n("88.4"))).isEqualByComparingTo("1");
        // a converted value is flagged like any other: 126 mg/dL is 7 mmol/L, against a band in mmol/L (TEST DATA limits)
        List<RangeBand> mmol = List.of(band("any", 0, null, "4", "6", null, null));
        assertThat(flag(mmol, "F", 1, glucose.apply(n("126")).toPlainString())).isEqualTo(Flag.HIGH);
        assertThat(flag(mmol, "F", 1, glucose.apply(n("90")).toPlainString())).isEqualTo(Flag.NORMAL);
        assertThatThrownBy(() -> new Conversion(BigDecimal.ZERO, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Conversion(BigDecimal.ONE, n("-18"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRegistersGenderMapsToABandSex() {
        assertThat(LabRangeEvaluator.sexOf("male")).isEqualTo("M");
        assertThat(LabRangeEvaluator.sexOf("female")).isEqualTo("F");
        assertThat(LabRangeEvaluator.sexOf("other")).isNull();
        assertThat(LabRangeEvaluator.sexOf(null)).isNull();
    }

    @Test
    void aBandIsCheckedBeforeItIsStored() {
        assertThat(LabRangeEvaluator.problem(band("F", 0, null, "12.0", "15.5", "7", "20"))).isNull();
        assertThat(LabRangeEvaluator.problem(band("F", 0, 28, "5", "5", null, null))).as("low and high may be equal").isNull();
        assertThat(LabRangeEvaluator.problem(new RangeBand("any", 0, null, null, null, null, null, "Negative"))).isNull();
        assertThat(LabRangeEvaluator.problem(band("F", 28, 28, "1", "2", null, null))).contains("upper age");
        assertThat(LabRangeEvaluator.problem(band("F", 28, 7, "1", "2", null, null))).contains("upper age");
        String order = "critical low, low, high, critical high";
        assertThat(LabRangeEvaluator.problem(band("F", 0, null, "15.5", "12.0", null, null))).contains(order);
        assertThat(LabRangeEvaluator.problem(band("F", 0, null, "12", "15", "12", null))).as("critical low equal to low").contains(order);
        assertThat(LabRangeEvaluator.problem(band("F", 0, null, "12", "15", null, "15"))).as("critical high equal to high").contains(order);
        assertThat(LabRangeEvaluator.problem(band("F", 0, null, null, "15", "16", null))).as("critical low above high").contains(order);
        assertThat(LabRangeEvaluator.problem(band("F", 0, null, null, null, "9", "2"))).contains(order);
        assertThat(LabRangeEvaluator.problem(band("F", 0, null, null, null, "9", "9"))).contains(order);
        assertThat(LabRangeEvaluator.problem(band("F", 0, null, null, null, null, null))).contains("at least one limit");
        assertThat(LabRangeEvaluator.problem(new RangeBand("F", 0, null, null, null, null, null, "  "))).contains("at least one limit");
    }

    private static List<Integer> overlap(RangeBand... bands) {
        return LabRangeEvaluator.overlap(List.of(bands)).map(p -> List.of(p[0], p[1])).orElse(null);
    }

    @Test
    void overlappingBandsAreFound() {
        RangeBand child = band("any", 0, 12 * YEAR, "1", "2", null, null);
        RangeBand women = band("F", 12 * YEAR, null, "1", "2", null, null);
        RangeBand men = band("M", 12 * YEAR, null, "1", "2", null, null);
        assertThat(overlap(child, women, men)).as("bands that meet, and two sexes over the same ages").isNull();
        assertThat(overlap()).isNull();
        assertThat(overlap(child, women, men, band("F", 60 * YEAR, null, "1", "2", null, null))).as("two open-ended female bands").containsExactly(1, 3);
        assertThat(overlap(women, band("any", 20 * YEAR, 30 * YEAR, "1", "2", null, null))).as("'any' covers women too").containsExactly(0, 1);
        assertThat(overlap(band("any", 20 * YEAR, 30 * YEAR, "1", "2", null, null), men)).containsExactly(0, 1);
        assertThat(overlap(child, band("M", 12 * YEAR - 1, 13 * YEAR, "1", "2", null, null))).as("one shared day").isNotNull();
        assertThat(overlap(band("M", 13 * YEAR, 14 * YEAR, "1", "2", null, null), child)).as("order does not matter: apart").isNull();
        assertThat(overlap(band("M", 5 * YEAR, 6 * YEAR, "1", "2", null, null), child)).as("order does not matter: inside").isNotNull();
        assertThat(overlap(band("M", 0, null, "1", "2", null, null), band("M", 0, null, "1", "2", null, null))).as("the same band twice").isNotNull();
    }
}
