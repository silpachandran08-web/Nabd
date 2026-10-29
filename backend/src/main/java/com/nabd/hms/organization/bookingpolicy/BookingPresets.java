package com.nabd.hms.organization.bookingpolicy;

import java.util.List;
import java.util.Map;

/** ACC-013: presets as versioned constants. Only Solo is authored; specialty/polyclinic/hospital arrive with ADM-009 (T-351). */
final class BookingPresets {

    static final int SOLO_VERSION = 1;

    private BookingPresets() {
    }

    /** What a clinic gets before it publishes anything: every booking auto-accepted, as before the policy existed. */
    static Map<String, Object> defaults() {
        return config(null, false, "auto", 200, "reception", List.of("desk", "door_qr", "whatsapp"), "range", 0, "none", 15, false, false, true, true);
    }

    /** B-20 / prototype 'Solo doctor': auto-accept cap 30 reviewed by the doctor, door QR, range ETA, 20 min margin, walk-ins 1:3, grace 10, no triage, no vitals. */
    static Map<String, Object> solo() {
        return config("solo", true, "auto", 30, "doctor", List.of("door_qr"), "range", 20, "1:3", 10, true, true, false, false);
    }

    static Map<String, Object> forName(String name) {
        return "solo".equals(name) ? solo() : null;
    }

    private static Map<String, Object> config(String preset, boolean solo, String acceptance, int cap, String reviewer, List<String> modes,
                                              String etaMode, int margin, String interleave, int grace, boolean autoMark, boolean pullForward,
                                              boolean vitals, boolean triage) {
        Map<String, Object> vts = new java.util.LinkedHashMap<>();
        for (String vt : BookingPolicyValidator.VISIT_TYPES) {
            vts.put(vt, Map.of("acceptance", acceptance, "dailyCapPerDoctor", cap, "reviewerRole", reviewer));
        }
        Map<String, Object> c = new java.util.LinkedHashMap<>();
        c.put("preset", preset);
        c.put("solo", solo);
        c.put("visitTypes", vts);
        c.put("checkIn", Map.of("modes", modes));
        c.put("eta", Map.of("displayMode", etaMode, "leaveHomeMarginMinutes", margin));
        c.put("walkIn", Map.of("interleave", interleave));
        c.put("noShow", Map.of("graceMinutes", grace, "autoMark", autoMark, "pullForward", pullForward, "depositAfterRepeat", false));
        c.put("workflow", Map.of("vitalsEnabled", vitals, "triageEnabled", triage));
        return c;
    }
}
