package com.nabd.hms.organization.bookingpolicy;

import com.nabd.hms.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ACC-008 unit: booking-policy.v1 schema validation and the cross-field rules (no Spring context). */
class BookingPolicyValidatorTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, v instanceof Map<?, ?> c ? deepCopy((Map<String, Object>) c) : v instanceof List<?> l ? new ArrayList<>(l) : v));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }

    private static void assertInvalid(Map<String, Object> cfg, String pointer, String rule) {
        assertThatThrownBy(() -> BookingPolicyValidator.validate(cfg)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status().value()).isEqualTo(400);
            assertThat(e.extras()).containsEntry("code", "INVALID_POLICY").containsEntry("pointer", pointer);
            if (rule != null) {
                assertThat(e.extras()).containsEntry("rule", rule);
            }
        });
    }

    @Test
    void theDefaultAndTheSoloPresetAreValid() {
        assertThat(BookingPolicyValidator.validate(BookingPresets.defaults())).containsEntry("solo", false);
        assertThat(BookingPolicyValidator.validate(BookingPresets.solo())).containsEntry("preset", "solo");
    }

    @Test
    void walkInInterleaveOneToSevenIsRejectedAtItsPointer() {
        Map<String, Object> cfg = deepCopy(BookingPresets.defaults());
        sub(cfg, "walkIn").put("interleave", "1:7");
        assertInvalid(cfg, "/walkIn/interleave", null);
    }

    @Test
    void soloRefusesReceptionOnlyCheckInAndForcesTheDoctorAsReviewer() {
        Map<String, Object> cfg = deepCopy(BookingPresets.defaults());
        cfg.put("solo", true);
        sub(cfg, "checkIn").put("modes", List.of("desk"));
        assertInvalid(cfg, "/checkIn/modes", "solo_requires_self_checkin");

        sub(cfg, "checkIn").put("modes", List.of("desk", "whatsapp"));
        Map<String, Object> ok = BookingPolicyValidator.validate(cfg);
        for (String vt : BookingPolicyValidator.VISIT_TYPES) {
            assertThat(sub(sub(ok, "visitTypes"), vt)).containsEntry("reviewerRole", "doctor");
        }
    }

    @Test
    void nonSoloKeepsTheChosenReviewer() {
        Map<String, Object> ok = BookingPolicyValidator.validate(BookingPresets.defaults());
        assertThat(sub(sub(ok, "visitTypes"), "new")).containsEntry("reviewerRole", "reception");
    }

    @Test
    void capsGraceAndUnknownKeysAreChecked() {
        Map<String, Object> cfg = deepCopy(BookingPresets.defaults());
        sub(sub(cfg, "visitTypes"), "followUp").put("dailyCapPerDoctor", 201);
        assertInvalid(cfg, "/visitTypes/followUp/dailyCapPerDoctor", "integer_1_to_200");

        cfg = deepCopy(BookingPresets.defaults());
        sub(sub(cfg, "visitTypes"), "followUp").put("dailyCapPerDoctor", 0);
        assertInvalid(cfg, "/visitTypes/followUp/dailyCapPerDoctor", null);

        cfg = deepCopy(BookingPresets.defaults());
        sub(cfg, "noShow").put("graceMinutes", 12);
        assertInvalid(cfg, "/noShow/graceMinutes", "must_be_10_15_or_20");

        cfg = deepCopy(BookingPresets.defaults());
        cfg.put("rulesEngine", Map.of());
        assertInvalid(cfg, "/rulesEngine", "unknown_property");

        cfg = deepCopy(BookingPresets.defaults());
        sub(sub(cfg, "visitTypes"), "new").put("acceptance", "maybe");
        assertInvalid(cfg, "/visitTypes/new/acceptance", null);

        cfg = deepCopy(BookingPresets.defaults());
        cfg.remove("noShow");
        assertInvalid(cfg, "/noShow", "required_object");
    }

    @Test
    void leaveHomeMarginNeedsATimeEstimate() {
        Map<String, Object> cfg = deepCopy(BookingPresets.defaults());
        sub(cfg, "eta").put("displayMode", "queue_position");
        sub(cfg, "eta").put("leaveHomeMarginMinutes", 20);
        assertInvalid(cfg, "/eta/leaveHomeMarginMinutes", "leave_home_needs_a_time_estimate");
        sub(cfg, "eta").put("leaveHomeMarginMinutes", 0);
        assertThat(BookingPolicyValidator.validate(cfg)).isNotNull();
    }

    @Test
    void diffListsOnlyChangedLeaves() {
        Map<String, Object> a = BookingPolicyValidator.validate(BookingPresets.defaults());
        Map<String, Object> bIn = deepCopy(a);
        sub(sub(bIn, "visitTypes"), "followUp").put("dailyCapPerDoctor", 40);
        var d = BookingPolicyService.diff(a, BookingPolicyValidator.validate(bIn));
        assertThat(d).singleElement().satisfies(c -> {
            assertThat(c.pointer()).isEqualTo("/visitTypes/followUp/dailyCapPerDoctor");
            assertThat(c.before()).isEqualTo(200);
            assertThat(c.after()).isEqualTo(40);
        });
    }
}
