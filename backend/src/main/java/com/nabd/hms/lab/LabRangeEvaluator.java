package com.nabd.hms.lab;

import com.nabd.hms.lab.LabRangeDtos.RangeBand;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Optional;

/**
 * PHL-042: picks the reference band for a patient and flags a result against it. Pure: no clock, no database. The value and
 * the band's limits are in the test's canonical unit; {@link Conversion} brings a value entered in another unit to it.
 * The bands of one range set never overlap (checked on save by {@link #overlap}), so at most one band fits a patient.
 */
public final class LabRangeEvaluator {

    /** code: what a report prints beside the value (L, H, C); empty for a normal value and for no reference range. */
    public enum Flag {
        NORMAL(""), LOW("L"), HIGH("H"), CRITICAL_LOW("C"), CRITICAL_HIGH("C"), NO_RANGE("");

        private final String code;

        Flag(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public boolean critical() {
            return this == CRITICAL_LOW || this == CRITICAL_HIGH;
        }
    }

    /** band: the band used, or null when none fits the patient. NO_RANGE with a band: the band has no low or high to compare
     * with (a text range, or critical limits only), so the value is shown with the band's text and never as normal. */
    public record Evaluation(Flag flag, RangeBand band) {
    }

    /** entered x multiply / divide = the value in the canonical unit: glucose mg/dL to mmol/L is (1, 18), creatinine mg/dL to
     * umol/L is (88.4, 1). inverse() turns a canonical value back for display. */
    public record Conversion(BigDecimal multiply, BigDecimal divide) {
        public Conversion {
            if (multiply.signum() <= 0 || divide.signum() <= 0) throw new IllegalArgumentException("A conversion factor must be above zero.");
        }

        public BigDecimal apply(BigDecimal value) {
            return value.multiply(multiply).divide(divide, MathContext.DECIMAL64);
        }

        public Conversion inverse() {
            return new Conversion(divide, multiply);
        }
    }

    private LabRangeEvaluator() {
    }

    /** The patient register's gender as a band's sex: M, F, or null (other or unknown: only an 'any' band fits). */
    public static String sexOf(String gender) {
        return "male".equals(gender) ? "M" : "female".equals(gender) ? "F" : null;
    }

    /** sex: M, F or null. ageDays: completed days of life, or null when unknown: then only a band for every age fits. */
    public static Optional<RangeBand> resolve(List<RangeBand> bands, String sex, Integer ageDays) {
        return bands.stream().filter(b -> fits(b, sex, ageDays)).findFirst();
    }

    private static boolean fits(RangeBand b, String sex, Integer ageDays) {
        if (!"any".equals(b.sex()) && !b.sex().equals(sex)) return false;
        if (ageDays == null) return b.ageMinDays() == 0 && b.ageMaxDays() == null;
        return ageDays >= b.ageMinDays() && (b.ageMaxDays() == null || ageDays < b.ageMaxDays());
    }

    /** Critical is checked first and includes the limit itself; low and high are the last normal values. */
    public static Evaluation evaluate(List<RangeBand> bands, String sex, Integer ageDays, BigDecimal value) {
        RangeBand b = resolve(bands, sex, ageDays).orElse(null);
        if (b == null) return new Evaluation(Flag.NO_RANGE, null);
        if (b.criticalLow() != null && value.compareTo(b.criticalLow()) <= 0) return new Evaluation(Flag.CRITICAL_LOW, b);
        if (b.criticalHigh() != null && value.compareTo(b.criticalHigh()) >= 0) return new Evaluation(Flag.CRITICAL_HIGH, b);
        if (b.low() != null && value.compareTo(b.low()) < 0) return new Evaluation(Flag.LOW, b);
        if (b.high() != null && value.compareTo(b.high()) > 0) return new Evaluation(Flag.HIGH, b);
        if (b.low() == null && b.high() == null) return new Evaluation(Flag.NO_RANGE, b);
        return new Evaluation(Flag.NORMAL, b);
    }

    /** What is wrong with one band, as a sentence for the person who typed it, or null. */
    public static String problem(RangeBand b) {
        if (b.ageMaxDays() != null && b.ageMaxDays() <= b.ageMinDays()) return "The upper age must be above the lower age.";
        BigDecimal[] limits = {b.criticalLow(), b.low(), b.high(), b.criticalHigh()};
        int last = -1;
        for (int i = 0; i < limits.length; i++) {
            if (limits[i] == null) continue;
            if (last >= 0) {
                int c = limits[last].compareTo(limits[i]);
                if (c > 0 || (c == 0 && !(last == 1 && i == 2))) { // only low and high may be equal
                    return "The limits must rise in this order: critical low, low, high, critical high.";
                }
            }
            last = i;
        }
        if (last < 0 && (b.text() == null || b.text().isBlank())) return "Enter at least one limit or a text range.";
        return null;
    }

    /** The first two bands (their positions) a patient could fall into at once: same or 'any' sex and age bands that meet. */
    public static Optional<int[]> overlap(List<RangeBand> bands) {
        for (int i = 0; i < bands.size(); i++) {
            for (int j = i + 1; j < bands.size(); j++) {
                RangeBand a = bands.get(i), b = bands.get(j);
                boolean sex = "any".equals(a.sex()) || "any".equals(b.sex()) || a.sex().equals(b.sex());
                boolean age = (b.ageMaxDays() == null || a.ageMinDays() < b.ageMaxDays()) && (a.ageMaxDays() == null || b.ageMinDays() < a.ageMaxDays());
                if (sex && age) return Optional.of(new int[]{i, j});
            }
        }
        return Optional.empty();
    }
}
