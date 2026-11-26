package com.nabd.hms.lab;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.lab.LabRangeEvaluator.Flag;
import com.nabd.hms.lab.LabRangeService.Flagged;
import com.nabd.hms.staff.presets.RolePresets;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHL-042: range sets per test: saved as versions by lab:validate, overlap refused, an old version stays readable, and the
 * module's flagging API reads the current one. Haemoglobin and potassium numbers are the story's examples; the rest is TEST DATA.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Phl042LabRangesApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000) + 100_000);
    private static final int YEAR = 365;

    @Autowired
    private LabRangeService ranges;

    private record Clinic(SeededTenant t, SeededStaff owner, String token) {
    }

    private String phone() {
        return "+9194" + (10000000 + SEQ.incrementAndGet());
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        UUID role = seedRole(t.id(), "Owner", true, fullGrant("lab"), fullGrant("billing"), fullGrant("setup"));
        SeededStaff m = seedStaff(t, role, "m" + SEQ.incrementAndGet() + "@p42.test", phone(), false);
        return new Clinic(t, m, loginAndGetAccessToken(m));
    }

    private String tokenWith(SeededTenant t, String roleName, List<ModuleGrant> grants) {
        UUID role = seedRole(t.id(), roleName + " " + SEQ.incrementAndGet(), false, grants.toArray(ModuleGrant[]::new));
        return loginAndGetAccessToken(seedStaff(t, role, "s" + SEQ.incrementAndGet() + "@p42.test", phone(), false));
    }

    private String testId(String token, String code, String unit) {
        ResponseEntity<Map> r = exchange("/v1/lab/tests", HttpMethod.POST, authedJsonBody(token, Map.of("code", code, "name", "Test " + code,
                "sampleType", "whole_blood", "container", "edta_lavender", "volumeMl", 2, "unit", unit, "tatMinutes", 240, "price", 100)), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private static Map<String, Object> band(String sex, int min, Integer max, String low, String high) {
        Map<String, Object> b = new HashMap<>(Map.of("sex", sex, "ageMinDays", min));
        if (max != null) b.put("ageMaxDays", max);
        if (low != null) b.put("low", new BigDecimal(low));
        if (high != null) b.put("high", new BigDecimal(high));
        return b;
    }

    private ResponseEntity<Map> put(String token, String testId, List<Map<String, Object>> bands) {
        return exchange("/v1/lab/tests/" + testId + "/ranges", HttpMethod.PUT, authedJsonBody(token, Map.of("ranges", bands)), Map.class);
    }

    private ResponseEntity<Map> get(String token, String testId, String query) {
        return exchange("/v1/lab/tests/" + testId + "/ranges" + query, HttpMethod.GET, authed(token), Map.class);
    }

    private int rows(UUID tenant, String testId) {
        return inTenantTx(tenant, () -> jdbc.queryForObject("SELECT count(*) FROM lab_reference_ranges WHERE tenant_id = ? AND test_id = ?",
                Integer.class, tenant, UUID.fromString(testId)));
    }

    private Flagged flag(Clinic c, String testId, String sex, Integer ageDays, String value) {
        return inTenantTx(c.t().id(), () -> ranges.flag(UUID.fromString(testId), sex, ageDays, new BigDecimal(value)));
    }

    private static List<Map<String, Object>> haemoglobin(String adultFemaleLow) {
        return List.of(band("any", 0, 12 * YEAR, "11.0", "14.5"), band("F", 12 * YEAR, null, adultFemaleLow, "15.5"), band("M", 12 * YEAR, null, "13.0", "17.0"));
    }

    // ---- AC1, AC2, AC5 through the stored set: the module API flags against what the lab saved ----
    @Test
    void ac1_ac2_ac5_aSavedRangeSetFlagsAWomanLowUsesTheChildBandAndGivesNoRangeWhenNoBandFits() {
        Clinic c = clinic();
        String hb = testId(c.token(), "HB", "g/dL");
        Flagged none = flag(c, hb, "F", 30 * YEAR, "11.2");
        assertThat(none.version()).as("no ranges yet").isZero();
        assertThat(none.evaluation().flag()).as("never a false normal").isEqualTo(Flag.NO_RANGE);
        Map empty = get(c.token(), hb, "").getBody();
        assertThat(empty.get("version")).isEqualTo(0);
        assertThat((List<?>) empty.get("ranges")).isEmpty();

        ResponseEntity<Map> saved = put(c.token(), hb, haemoglobin("12.0"));
        assertThat(saved.getStatusCode()).as("%s", saved.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody().get("version")).isEqualTo(1);
        assertThat(saved.getBody().get("effectiveFrom")).isNotNull();

        Flagged woman = flag(c, hb, LabRangeEvaluator.sexOf("female"), 30 * YEAR, "11.2");
        assertThat(woman.evaluation().flag()).isEqualTo(Flag.LOW);
        assertThat(woman.evaluation().band().low()).isEqualByComparingTo("12.0");
        assertThat(woman.version()).isEqualTo(1);
        Flagged child = flag(c, hb, "F", 6 * YEAR, "11.5");
        assertThat(child.evaluation().flag()).as("the child band, not the adult female one").isEqualTo(Flag.NORMAL);
        assertThat(child.evaluation().band().high()).isEqualByComparingTo("14.5");

        // a set with a band for women only: a man gets no reference range
        String fer = testId(c.token(), "FER", "ng/mL");
        assertThat(put(c.token(), fer, List.of(band("F", 0, null, "10", "150"))).getStatusCode()).isEqualTo(HttpStatus.OK);
        Flagged man = flag(c, fer, "M", 30 * YEAR, "50");
        assertThat(man.evaluation().flag()).isEqualTo(Flag.NO_RANGE);
        assertThat(man.evaluation().band()).isNull();
        assertThat(flag(c, fer, "F", 30 * YEAR, "50").evaluation().flag()).isEqualTo(Flag.NORMAL);
    }

    // ---- AC3: the critical limit is stored and flags critical (the PHL-050 alert itself is not built) ----
    @Test
    void ac3_potassiumAboveItsCriticalHighIsFlaggedCritical() {
        Clinic c = clinic();
        String k = testId(c.token(), "K", "mmol/L");
        Map<String, Object> b = band("any", 0, null, "3.5", "5.1");
        b.put("criticalLow", new BigDecimal("2.5"));
        b.put("criticalHigh", new BigDecimal("6.0"));
        b.put("text", "  Haemolysed samples read high  ");
        ResponseEntity<Map> saved = put(c.token(), k, List.of(b));
        assertThat(saved.getStatusCode()).as("%s", saved.getBody()).isEqualTo(HttpStatus.OK);
        Map<String, Object> stored = ((List<Map<String, Object>>) saved.getBody().get("ranges")).get(0);
        assertThat(((Number) stored.get("criticalHigh")).doubleValue()).isEqualTo(6.0);
        assertThat(stored.get("text")).isEqualTo("Haemolysed samples read high");
        assertThat(stored.get("ageMaxDays")).isNull();

        Flagged f = flag(c, k, "M", 50 * YEAR, "6.6");
        assertThat(f.evaluation().flag()).isEqualTo(Flag.CRITICAL_HIGH);
        assertThat(f.evaluation().flag().code()).isEqualTo("C");
        assertThat(f.evaluation().flag().critical()).isTrue();
        assertThat(flag(c, k, null, null, "5.5").evaluation().flag()).isEqualTo(Flag.HIGH);
    }

    // ---- AC4: an edit is a new version; the earlier version stays as it was and stays readable ----
    @Test
    void ac4_aRangeEditCreatesANewVersionAndTheEarlierOneStaysReadable() {
        Clinic c = clinic();
        UUID t = c.t().id();
        String hb = testId(c.token(), "HB", "g/dL");
        assertThat(put(c.token(), hb, haemoglobin("12.0")).getBody().get("version")).isEqualTo(1);
        Flagged before = flag(c, hb, "F", 30 * YEAR, "11.7");
        assertThat(before.evaluation().flag()).isEqualTo(Flag.LOW);
        assertThat(before.version()).isEqualTo(1);
        List<Map<String, Object>> v1Rows = inTenantTx(t, () -> jdbc.queryForList(
                "SELECT id, low, effective_from FROM lab_reference_ranges WHERE tenant_id = ? AND version = 1 ORDER BY id", t));

        ResponseEntity<Map> edited = put(c.token(), hb, haemoglobin("11.5"));
        assertThat(edited.getStatusCode()).as("%s", edited.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(edited.getBody().get("version")).isEqualTo(2);
        assertThat(rows(t, hb)).as("both versions are stored").isEqualTo(6);

        // new results use version 2 ...
        Flagged after = flag(c, hb, "F", 30 * YEAR, "11.7");
        assertThat(after.version()).isEqualTo(2);
        assertThat(after.evaluation().flag()).isEqualTo(Flag.NORMAL);
        Map current = get(c.token(), hb, "").getBody();
        assertThat(current.get("version")).isEqualTo(2);
        // ... and the version the earlier result was flagged with still shows 12.0, row for row
        Map v1 = get(c.token(), hb, "?version=1").getBody();
        assertThat(v1.get("version")).isEqualTo(1);
        List<Map<String, Object>> bands = (List<Map<String, Object>>) v1.get("ranges");
        assertThat(bands).hasSize(3);
        Map<String, Object> women = bands.stream().filter(x -> "F".equals(x.get("sex"))).findFirst().orElseThrow();
        assertThat(((Number) women.get("low")).doubleValue()).isEqualTo(12.0);
        assertThat(inTenantTx(t, () -> jdbc.queryForList(
                "SELECT id, low, effective_from FROM lab_reference_ranges WHERE tenant_id = ? AND version = 1 ORDER BY id", t)))
                .as("version 1 was not touched by the edit").isEqualTo(v1Rows);
        assertThat(get(c.token(), hb, "?version=3").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(c.token(), hb, "?version=0").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // each save is audited with the version numbers and the actor
        List<Map<String, Object>> audits = inTenantTx(t, () -> jdbc.queryForList("SELECT actor_id, before::text AS b, after::text AS a FROM audit_log "
                + "WHERE tenant_id = ? AND action = 'lab.ranges_replaced' AND entity_id = ? ORDER BY created_at", t, UUID.fromString(hb)));
        assertThat(audits).hasSize(2);
        assertThat(audits.get(1).get("actor_id")).isEqualTo(c.owner().id());
        assertThat((String) audits.get(1).get("b")).contains("\"version\": 1");
        assertThat((String) audits.get(1).get("a")).contains("\"version\": 2");
    }

    @Test
    void overlappingOrBrokenBandsAreRefusedAndNothingIsStored() {
        Clinic c = clinic();
        String hb = testId(c.token(), "HB", "g/dL");
        assertThat(put(c.token(), hb, haemoglobin("12.0")).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> overlap = put(c.token(), hb, List.of(band("any", 0, 12 * YEAR, "11.0", "14.5"), band("F", 10 * YEAR, null, "12.0", "15.5")));
        assertThat(overlap.getStatusCode().value()).as("%s", overlap.getBody()).isEqualTo(422);
        assertThat(String.valueOf(overlap.getBody().get("type"))).endsWith("/overlapping-bands");
        assertThat(overlap.getBody().get("code")).isEqualTo("OVERLAPPING_BANDS");
        assertThat((List<Integer>) overlap.getBody().get("bands")).containsExactly(0, 1);
        assertThat((String) overlap.getBody().get("detail")).as("a sentence naming the bands").contains("Bands 1 and 2");

        ResponseEntity<Map> order = put(c.token(), hb, List.of(band("any", 0, null, "15.5", "12.0")));
        assertThat(order.getStatusCode().value()).isEqualTo(422);
        assertThat(String.valueOf(order.getBody().get("type"))).endsWith("/invalid-band");
        assertThat(order.getBody().get("band")).isEqualTo(0);
        assertThat(put(c.token(), hb, List.of(band("any", 28, 28, "1", "2"))).getStatusCode().value()).as("upper age not above lower").isEqualTo(422);
        assertThat(put(c.token(), hb, List.of(band("any", 0, null, null, null))).getStatusCode().value()).as("a band with nothing in it").isEqualTo(422);

        assertThat(put(c.token(), hb, List.of(band("female", 0, null, "1", "2"))).getStatusCode()).as("unknown sex").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(c.token(), hb, List.of(band("F", -1, null, "1", "2"))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(c.token(), hb, List.of()).getStatusCode()).as("an empty set").isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(rows(c.t().id(), hb)).as("only the first, valid set is stored").isEqualTo(3);
        assertThat(get(c.token(), hb, "").getBody().get("version")).isEqualTo(1);
    }

    @Test
    void onlyLabValidateMaySave_viewersRead_andAnotherClinicSeesNothing() {
        Clinic a = clinic(), b = clinic();
        String hb = testId(a.token(), "HB", "g/dL");
        assertThat(put(a.token(), hb, haemoglobin("12.0")).getStatusCode()).isEqualTo(HttpStatus.OK);

        // the lab technician has lab:view but not lab:validate
        String tech = tokenWith(a.t(), "Lab technician", RolePresets.byCode("lab_technician").orElseThrow().grants());
        assertThat(get(tech, hb, "").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put(tech, hb, haemoglobin("11.0")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // the catalogue grant alone (lab manager without validation) does not change ranges either
        ModuleGrant catalogueOnly = new ModuleGrant("lab", true, false, false, true, false, false, false, false);
        assertThat(put(tokenWith(a.t(), "Lab manager", List.of(catalogueOnly)), hb, haemoglobin("11.0")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String desk = tokenWith(a.t(), "Receptionist", RolePresets.byCode("receptionist").orElseThrow().grants());
        assertThat(get(desk, hb, "").getStatusCode()).as("no lab:view").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rows(a.t().id(), hb)).isEqualTo(3);

        // another clinic: the test is unknown there, for reading and for saving, and its own flagging finds no set
        assertThat(get(b.token(), hb, "").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(put(b.token(), hb, haemoglobin("9.0")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(put(b.token(), UUID.randomUUID().toString(), haemoglobin("9.0")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(flag(b, hb, "F", 30 * YEAR, "11.2").version()).isZero();
        assertThat(inTenantTx(b.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM lab_reference_ranges", Integer.class))).as("row-level security").isZero();
        assertThat(rows(a.t().id(), hb)).isEqualTo(3);
        assertThat(get(a.token(), hb, "").getBody().get("version")).isEqualTo(1);
    }
}
