package com.nabd.hms.organization.bookingpolicy;

import com.nabd.hms.organization.api.OrganizationConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-013: the Solo preset in one step; department workflow sync on publish; idempotent; customised label; no clinical or billing data touched. */
class Acc013SoloPresetApiTest extends BookingPolicyTestSupport {

    @Autowired OrganizationConfig orgConfig;

    private UUID defaultDepartment(UUID tenantId) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject("SELECT id FROM departments WHERE is_default", UUID.class));
    }

    private List<String> sequence(UUID tenantId) {
        UUID dept = defaultDepartment(tenantId);
        return inTenantTx(tenantId, () -> orgConfig.resolveStatusSequence(tenantId, dept));
    }

    @Test
    @SuppressWarnings("unchecked")
    void applyingAndPublishingSoloMatchesThePresetAndDropsTheVitalsStage() throws Exception {
        Clinic c = clinic();
        assertThat(sequence(c.tenant().id())).contains("vitals_pending", "vitals_done"); // before: the platform default

        long t0 = System.nanoTime();
        ResponseEntity<Map> applied = applyPreset(c.token(), "solo");
        assertThat((System.nanoTime() - t0) / 1_000_000_000.0).isLessThan(2.0); // the list of changes within 2 s
        assertThat(applied.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> cfg = sub(sub(applied.getBody(), "draft"), "config");
        assertThat(cfg).containsEntry("preset", "solo").containsEntry("solo", true).containsEntry("presetCustomised", false);
        assertThat(sub(sub(cfg, "visitTypes"), "followUp")).containsEntry("acceptance", "auto").containsEntry("dailyCapPerDoctor", 30).containsEntry("reviewerRole", "doctor");
        assertThat(sub(cfg, "checkIn")).containsEntry("modes", List.of("door_qr"));
        assertThat(sub(cfg, "eta")).containsEntry("displayMode", "range").containsEntry("leaveHomeMarginMinutes", 20);
        assertThat(sub(cfg, "walkIn")).containsEntry("interleave", "1:3");
        assertThat(sub(cfg, "noShow")).containsEntry("graceMinutes", 10).containsEntry("autoMark", true).containsEntry("pullForward", true);
        assertThat(sub(cfg, "workflow")).containsEntry("vitalsEnabled", false).containsEntry("triageEnabled", false);

        List<Map<String, Object>> changes = (List<Map<String, Object>>) applied.getBody().get("changes");
        assertThat(changes).extracting(m -> m.get("pointer")).contains("/solo", "/checkIn/modes", "/workflow/vitalsEnabled", "/walkIn/interleave");
        List<Map<String, Object>> deptChanges = (List<Map<String, Object>>) applied.getBody().get("departmentWorkflowChanges");
        assertThat(deptChanges).singleElement().satisfies(d -> assertThat(d).containsEntry("toVitalsEnabled", false));
        assertThat(sequence(c.tenant().id())).contains("vitals_pending"); // applying fills the draft only

        assertThat(publish(c.token(), "Solo doctor").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publishedConfig(c.token())).isEqualTo(cfg);
        assertThat(sequence(c.tenant().id())).containsExactly("checked_in", "waiting", "in_consult", "checkout_pending", "completed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void reApplyingIsIdempotentAndTouchesNoVisitData() throws Exception {
        Clinic c = clinic();
        workingHoursTomorrow(c);
        String appt = (String) book(c, patient(c, "Pat Solo"), slot(c.tenant().id(), 9, 0)).getBody().get("id");
        Map<String, Object> rowBefore = inTenantTx(c.tenant().id(), () -> jdbc.queryForMap(
                "SELECT status, start_time, end_time, patient_id, doctor_id, cancel_reason FROM appointments WHERE id = ?::uuid", appt));
        int queueBefore = inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM queue_entries", Integer.class));
        int invoicesBefore = inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM invoices", Integer.class));

        applyPreset(c.token(), "solo");
        publish(c.token(), null);
        ResponseEntity<Map> again = applyPreset(c.token(), "solo");
        assertThat((List<?>) again.getBody().get("changes")).isEmpty();
        assertThat((List<?>) again.getBody().get("departmentWorkflowChanges")).isEmpty();
        publish(c.token(), "re-applied");

        assertThat(sequence(c.tenant().id())).doesNotContain("vitals_pending");
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForMap(
                "SELECT status, start_time, end_time, patient_id, doctor_id, cancel_reason FROM appointments WHERE id = ?::uuid", appt))).isEqualTo(rowBefore);
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM queue_entries", Integer.class))).isEqualTo(queueBefore);
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM invoices", Integer.class))).isEqualTo(invoicesBefore);
    }

    @Test
    @SuppressWarnings("unchecked")
    void turningVitalsBackOnChangesOnlyThatToggleAndMarksTheClinicCustomised() throws Exception {
        Clinic c = clinic();
        applyPreset(c.token(), "solo");
        publish(c.token(), null);

        Map<String, Object> cfg = copy(publishedConfig(c.token()));
        sub(cfg, "workflow").put("vitalsEnabled", true);
        ResponseEntity<Map> saved = putDraft(c.token(), cfg);
        assertThat(sub(sub(saved.getBody(), "draft"), "config")).containsEntry("presetCustomised", true).containsEntry("preset", "solo");
        publish(c.token(), "vitals on");

        assertThat(sequence(c.tenant().id())).contains("vitals_pending", "vitals_done", "in_consult");
        Map<String, Object> body = exchange("/v1/booking-policy/versions", HttpMethod.GET, authed(c.token()), Map.class).getBody();
        List<Map<String, Object>> diff = (List<Map<String, Object>>) ((Map<String, Object>) ((List<?>) body.get("items")).get(0)).get("diff");
        assertThat(diff).extracting(d -> d.get("pointer")).containsExactlyInAnyOrder("/workflow/vitalsEnabled", "/presetCustomised");
    }

    @Test
    void onlySoloIsAvailableOthersAreUnknownPresets() {
        Clinic c = clinic();
        for (String name : List.of("hospital", "banana")) {
            ResponseEntity<Map> r = applyPreset(c.token(), name);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(r.getBody()).containsEntry("code", "UNKNOWN_PRESET");
        }
        assertThat(current(c.token()).getBody().get("draft")).isNull();
    }

    @Test
    void soloDoesNotTouchAnotherClinicsDepartments() throws Exception {
        Clinic a = clinic();
        Clinic b = clinic();
        applyPreset(a.token(), "solo");
        publish(a.token(), null);
        assertThat(sequence(b.tenant().id())).contains("vitals_pending");
    }
}
