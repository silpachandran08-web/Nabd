package com.nabd.hms.pharmacy;

import java.util.List;

/** PHL-004: the three pharmacy models, ordered by what they switch on. */
public enum PharmacyMode {
    EXTERNAL("external", List.of()),
    HYBRID("hybrid", List.of("items")),
    IN_HOUSE("in_house", List.of("items", "batches", "worklist", "registers", "procurement", "offline"));

    final String code;
    final List<String> features;

    PharmacyMode(String code, List<String> features) {
        this.code = code;
        this.features = features;
    }

    public String code() {
        return code;
    }

    static PharmacyMode of(String code) {
        for (PharmacyMode m : values()) {
            if (m.code.equals(code)) {
                return m;
            }
        }
        return EXTERNAL;
    }
}
