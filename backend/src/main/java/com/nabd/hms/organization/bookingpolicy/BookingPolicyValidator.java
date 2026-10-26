package com.nabd.hms.organization.bookingpolicy;

import com.nabd.hms.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ACC-008: booking-policy.v1. Owners pick from fixed options (V40, CAD-06), so the schema is closed: unknown keys, wrong types,
 * out-of-range values and the cross-field rules all fail with 400 INVALID_POLICY {pointer (JSON pointer), rule}.
 * Returns a normalised copy (solo forces every reviewer to 'doctor'); never mutates the input.
 */
final class BookingPolicyValidator {

    static final List<String> VISIT_TYPES = List.of("new", "followUp", "procedure", "teleconsult");
    static final Set<String> ACCEPTANCE = Set.of("auto", "review", "hybrid");
    static final Set<String> REVIEWERS = Set.of("doctor", "reception", "nurse", "owner");
    static final Set<String> CHECK_IN = Set.of("door_qr", "whatsapp", "geofence", "desk");
    static final Set<String> SELF_CHECK_IN = Set.of("door_qr", "whatsapp", "geofence");
    static final Set<String> ETA_MODES = Set.of("exact", "range", "queue_position");
    static final Set<String> INTERLEAVE = Set.of("1:2", "1:3", "1:4", "reserved_only", "none");
    static final Set<Integer> GRACE = Set.of(10, 15, 20);
    static final Set<String> PRESETS = Set.of("solo", "specialty_clinic", "polyclinic", "hospital");

    private BookingPolicyValidator() {
    }

    static Map<String, Object> validate(Map<String, Object> in) {
        if (in == null) {
            throw bad("", "required");
        }
        keys("", in, Set.of("preset", "solo", "visitTypes", "checkIn", "eta", "walkIn", "noShow", "workflow", "presetCustomised"));
        Map<String, Object> out = new LinkedHashMap<>();
        Object preset = in.get("preset");
        if (preset != null && !(preset instanceof String p && PRESETS.contains(p))) {
            throw bad("/preset", "unknown_preset");
        }
        out.put("preset", preset);
        boolean solo = bool(in, "", "solo");
        out.put("solo", solo);

        Map<String, Object> vts = obj(in, "", "visitTypes");
        keys("/visitTypes", vts, Set.copyOf(VISIT_TYPES));
        Map<String, Object> vtOut = new LinkedHashMap<>();
        for (String vt : VISIT_TYPES) {
            String base = "/visitTypes/" + vt;
            Map<String, Object> v = obj(vts, "/visitTypes", vt);
            keys(base, v, Set.of("acceptance", "dailyCapPerDoctor", "reviewerRole"));
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("acceptance", oneOf(v, base, "acceptance", ACCEPTANCE));
            o.put("dailyCapPerDoctor", intIn(v, base, "dailyCapPerDoctor", 1, 200));
            String reviewer = oneOf(v, base, "reviewerRole", REVIEWERS);
            o.put("reviewerRole", solo ? "doctor" : reviewer); // solo: the doctor is the only reviewer
            vtOut.put(vt, o);
        }
        out.put("visitTypes", vtOut);

        Map<String, Object> ci = obj(in, "", "checkIn");
        keys("/checkIn", ci, Set.of("modes"));
        Object modes = ci.get("modes");
        if (!(modes instanceof List<?> l) || l.isEmpty() || l.size() != Set.copyOf(l).size()
                || !l.stream().allMatch(m -> m instanceof String s && CHECK_IN.contains(s))) {
            throw bad("/checkIn/modes", "invalid_check_in_modes");
        }
        if (solo && l.stream().noneMatch(SELF_CHECK_IN::contains)) {
            throw bad("/checkIn/modes", "solo_requires_self_checkin");
        }
        out.put("checkIn", Map.of("modes", new ArrayList<>(l)));

        Map<String, Object> eta = obj(in, "", "eta");
        keys("/eta", eta, Set.of("displayMode", "leaveHomeMarginMinutes"));
        String mode = oneOf(eta, "/eta", "displayMode", ETA_MODES);
        int margin = intIn(eta, "/eta", "leaveHomeMarginMinutes", 0, 120);
        if (margin > 0 && "queue_position".equals(mode)) {
            throw bad("/eta/leaveHomeMarginMinutes", "leave_home_needs_a_time_estimate");
        }
        out.put("eta", Map.of("displayMode", mode, "leaveHomeMarginMinutes", margin));

        Map<String, Object> wi = obj(in, "", "walkIn");
        keys("/walkIn", wi, Set.of("interleave"));
        out.put("walkIn", Map.of("interleave", oneOf(wi, "/walkIn", "interleave", INTERLEAVE)));

        Map<String, Object> ns = obj(in, "", "noShow");
        keys("/noShow", ns, Set.of("graceMinutes", "autoMark", "pullForward", "depositAfterRepeat"));
        Map<String, Object> nsOut = new LinkedHashMap<>();
        int grace = intIn(ns, "/noShow", "graceMinutes", 10, 20);
        if (!GRACE.contains(grace)) {
            throw bad("/noShow/graceMinutes", "must_be_10_15_or_20");
        }
        nsOut.put("graceMinutes", grace);
        nsOut.put("autoMark", bool(ns, "/noShow", "autoMark"));
        nsOut.put("pullForward", bool(ns, "/noShow", "pullForward"));
        nsOut.put("depositAfterRepeat", ns.containsKey("depositAfterRepeat") ? bool(ns, "/noShow", "depositAfterRepeat") : false); // flag only; billing owns deposits
        out.put("noShow", nsOut);

        Map<String, Object> wf = obj(in, "", "workflow");
        keys("/workflow", wf, Set.of("vitalsEnabled", "triageEnabled"));
        out.put("workflow", Map.of("vitalsEnabled", bool(wf, "/workflow", "vitalsEnabled"),
                "triageEnabled", bool(wf, "/workflow", "triageEnabled")));
        return out;
    }

    private static void keys(String base, Map<String, Object> m, Set<String> allowed) {
        for (String k : m.keySet()) {
            if (!allowed.contains(k)) {
                throw bad(base + "/" + k, "unknown_property");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Map<String, Object> m, String base, String key) {
        if (!(m.get(key) instanceof Map<?, ?> o)) {
            throw bad(base + "/" + key, "required_object");
        }
        return (Map<String, Object>) o;
    }

    private static String oneOf(Map<String, Object> m, String base, String key, Set<String> allowed) {
        if (!(m.get(key) instanceof String s) || !allowed.contains(s)) {
            throw bad(base + "/" + key, "must_be_one_of_" + String.join("|", allowed.stream().sorted().toList()));
        }
        return s;
    }

    private static int intIn(Map<String, Object> m, String base, String key, int min, int max) {
        if (!(m.get(key) instanceof Integer i) || i < min || i > max) {
            throw bad(base + "/" + key, "integer_" + min + "_to_" + max);
        }
        return i;
    }

    private static boolean bool(Map<String, Object> m, String base, String key) {
        if (!(m.get(key) instanceof Boolean b)) {
            throw bad(base + "/" + key, "required_boolean");
        }
        return b;
    }

    static ApiException bad(String pointer, String rule) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid-policy", "Invalid booking policy",
                "The booking policy is not valid at " + (pointer.isEmpty() ? "/" : pointer) + ".",
                Map.of("code", "INVALID_POLICY", "pointer", pointer.isEmpty() ? "/" : pointer, "rule", rule));
    }
}
