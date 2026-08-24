package com.nabd.hms.clinical;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.nabd.hms.clinical.VitalsModels.VitalsRow;

/**
 * NB-106: age-banded normal ranges for flagging obviously abnormal values — a simplified clinical
 * heuristic (three age bands, one range per vital), not a validated medical reference. Good enough
 * to surface "this needs a look," never meant to replace clinical judgement.
 */
final class VitalsRanges {

    private VitalsRanges() {
    }

    private record Range(int pulseLow, int pulseHigh, int systolicLow, int systolicHigh) {
    }

    private static Range bandFor(int ageYears) {
        if (ageYears < 12) return new Range(70, 120, 70, 110);
        if (ageYears < 65) return new Range(60, 100, 90, 140);
        return new Range(60, 100, 90, 150);
    }

    static List<String> flags(VitalsRow v, int ageYears) {
        Range r = bandFor(ageYears);
        List<String> flags = new ArrayList<>();
        if (v.pulseBpm() != null && (v.pulseBpm() < r.pulseLow() || v.pulseBpm() > r.pulseHigh())) {
            flags.add("Pulse " + v.pulseBpm() + " bpm outside " + r.pulseLow() + "-" + r.pulseHigh() + " for age");
        }
        if (v.bpSystolic() != null && (v.bpSystolic() < r.systolicLow() || v.bpSystolic() > r.systolicHigh())) {
            flags.add("BP systolic " + v.bpSystolic() + " outside " + r.systolicLow() + "-" + r.systolicHigh() + " for age");
        }
        if (v.tempCelsius() != null && (v.tempCelsius().compareTo(BigDecimal.valueOf(36.0)) < 0
                || v.tempCelsius().compareTo(BigDecimal.valueOf(37.8)) > 0)) {
            flags.add("Temp " + v.tempCelsius() + "°C outside 36.0-37.8");
        }
        if (v.spo2Percent() != null && v.spo2Percent() < 95) {
            flags.add("SpO2 " + v.spo2Percent() + "% below 95");
        }
        return flags;
    }
}
