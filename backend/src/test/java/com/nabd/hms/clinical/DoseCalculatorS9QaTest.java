package com.nabd.hms.clinical;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA (S9 CLN-003): dose calculator age guard, staleness windows, refusals, permission, tenant isolation. */
class DoseCalculatorS9QaTest extends ApiTestBase {

    private int seq = 0;

    private record Ctx(SeededTenant tenant, SeededStaff staff, String token, ZoneId zone) {
        LocalDate today() { return LocalDate.now(zone); }
    }

    private Ctx ctx() {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "qa" + System.nanoTime() + "@a.com", "+9198" + (10000000 + (int) (System.nanoTime() % 80000000)), false);
        String tz = jdbc.queryForObject("SELECT timezone FROM tenants WHERE id = ?", String.class, t.id());
        return new Ctx(t, s, loginAndGetAccessToken(s), ZoneId.of(tz == null ? "UTC" : tz));
    }

    private String patient(Ctx c, LocalDate dob) {
        seq++;
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "name", "QA Dose " + seq, "phone", "+91999" + (9000000 + (int) (System.nanoTime() % 900000)) , "dob", "1990-01-01", "gender", "male")), Map.class);
        String id = (String) r.getBody().get("id");
        inTenantTx(c.tenant.id(), () -> jdbc.update("UPDATE patients SET dob = ?::date WHERE id = ?::uuid", dob.toString(), id));
        return id;
    }

    private void weigh(Ctx c, String patientId, double kg) {
        String q = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "patientId", patientId, "doctorId", c.staff.id().toString())), Map.class).getBody().get("id");
        for (String s : new String[]{"waiting", "vitals_pending"}) {
            exchange("/v1/queue/" + q + "/status", HttpMethod.PATCH, authedJsonBody(c.token, Map.of("status", s)), Map.class);
        }
        ResponseEntity<Map> v = exchange("/v1/clinical/vitals/" + q, HttpMethod.PATCH, authedJsonBody(c.token, Map.of("weightKg", kg)), Map.class);
        assertThat(v.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Weight recorded N clinic-calendar days ago, at local noon (away from midnight edge). */
    private void recordedDaysAgo(Ctx c, String patientId, int days) {
        String when = c.today().minusDays(days) + " 12:00:00";
        inTenantTx(c.tenant.id(), () -> jdbc.update(
                "UPDATE vitals SET weight_recorded_at = (?::timestamp AT TIME ZONE ?) WHERE patient_id = ?::uuid", when, c.zone.getId(), patientId));
    }

    private ResponseEntity<Map> dose(Ctx c, String patientId, String mg) {
        return exchange("/v1/clinical/patients/" + patientId + "/dose-calculator?mgPerKg=" + mg, HttpMethod.GET, authed(c.token), Map.class);
    }

    private static boolean stale(ResponseEntity<Map> r) {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (Boolean) r.getBody().get("weightStale");
    }

    @Test
    void adultBoundary_17y364d_isServed_18y0d_isRefused422() {
        Ctx c = ctx();
        String child = patient(c, c.today().minusYears(18).plusDays(1));
        weigh(c, child, 40.0);
        ResponseEntity<Map> ok = dose(c, child, "10");
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) ok.getBody().get("totalDoseMg")).doubleValue()).isEqualTo(400.0);

        String adult = patient(c, c.today().minusYears(18));
        weigh(c, adult, 70.0); // a weight on file must not matter
        ResponseEntity<Map> r = dose(c, adult, "10");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) r.getBody().get("type")).endsWith("adult-patient");
        assertThat(r.getBody().get("totalDoseMg")).isNull();

        String old = patient(c, c.today().minusYears(60));
        assertThat(dose(c, old, "10").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT); // adult check precedes no-weight 400
    }

    @Test
    void fourteenMonthOld_40DayWeight_isStale_withRecordedAt() {
        Ctx c = ctx();
        String p = patient(c, c.today().minusMonths(14));
        weigh(c, p, 10.2);
        recordedDaysAgo(c, p, 40);
        ResponseEntity<Map> r = dose(c, p, "10");
        assertThat(stale(r)).isTrue();
        assertThat(r.getBody().get("weightRecordedAt")).isNotNull();
        assertThat(r.getBody().get("weightRecordedAt").toString()).contains(c.today().minusDays(40).toString().substring(0, 7));
    }

    @Test
    void weightRecordedToday_isFresh_andRuleSourceIdenticalToStaleResponse() {
        Ctx c = ctx();
        String p = patient(c, c.today().minusMonths(14));
        weigh(c, p, 10.2);
        ResponseEntity<Map> fresh = dose(c, p, "10");
        assertThat(stale(fresh)).isFalse();
        assertThat(((Number) fresh.getBody().get("totalDoseMg")).doubleValue()).isEqualTo(102.0);
        String src = (String) fresh.getBody().get("ruleSource");
        assertThat(src).isEqualTo("Weight-based mg/kg calculation v1 (clinician-supplied rate; no coded per-drug formulary)");
        recordedDaysAgo(c, p, 200);
        assertThat(dose(c, p, "10").getBody().get("ruleSource")).isEqualTo(src);
    }

    @Test
    void infantWindow_30IsFresh_31IsStale() {
        Ctx c = ctx();
        String p = patient(c, c.today().minusMonths(23));
        weigh(c, p, 11.0);
        recordedDaysAgo(c, p, 30);
        assertThat(stale(dose(c, p, "5"))).isFalse();
        recordedDaysAgo(c, p, 31);
        assertThat(stale(dose(c, p, "5"))).isTrue();
    }

    @Test
    void olderBand_90IsFresh_91IsStale_andExactly2yearsUsesOlderBand() {
        Ctx c = ctx();
        String two = patient(c, c.today().minusYears(2)); // 2y0d -> 90-day window
        weigh(c, two, 12.0);
        recordedDaysAgo(c, two, 90);
        assertThat(stale(dose(c, two, "5"))).isFalse();
        recordedDaysAgo(c, two, 91);
        assertThat(stale(dose(c, two, "5"))).isTrue();

        String almostTwo = patient(c, c.today().minusYears(2).plusDays(1)); // 1y364d -> 30-day window
        weigh(c, almostTwo, 12.0);
        recordedDaysAgo(c, almostTwo, 31);
        assertThat(stale(dose(c, almostTwo, "5"))).isTrue();

        String teen = patient(c, c.today().minusYears(17));
        weigh(c, teen, 55.0);
        recordedDaysAgo(c, teen, 90);
        assertThat(stale(dose(c, teen, "5"))).isFalse();
        recordedDaysAgo(c, teen, 91);
        assertThat(stale(dose(c, teen, "5"))).isTrue();
    }

    @Test
    void unknownRecordingTime_isStale_neverFresh() {
        Ctx c = ctx();
        String p = patient(c, c.today().minusYears(5));
        weigh(c, p, 18.0);
        inTenantTx(c.tenant.id(), () -> jdbc.update("UPDATE vitals SET weight_recorded_at = NULL WHERE patient_id = ?::uuid", p));
        ResponseEntity<Map> r = dose(c, p, "5");
        assertThat(stale(r)).isTrue();
        assertThat(r.getBody().get("weightRecordedAt")).isNull();
    }

    @Test
    void newerWeightReplacesStaleOne() {
        Ctx c = ctx();
        String p = patient(c, c.today().minusMonths(10));
        weigh(c, p, 9.0);
        recordedDaysAgo(c, p, 60);
        assertThat(stale(dose(c, p, "5"))).isTrue();
        weigh(c, p, 9.4);
        ResponseEntity<Map> r = dose(c, p, "5");
        assertThat(stale(r)).isFalse();
        assertThat(((Number) r.getBody().get("weightKg")).doubleValue()).isEqualTo(9.4);
    }

    @Test
    void noWeight_is400_noWeightRecorded() {
        Ctx c = ctx();
        String p = patient(c, c.today().minusYears(4));
        ResponseEntity<Map> r = dose(c, p, "10");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) r.getBody().get("type")).endsWith("no-weight-recorded");
    }

    @Test
    void unknownPatient_404_andMissingRate_400() {
        Ctx c = ctx();
        assertThat(exchange("/v1/clinical/patients/" + UUID.randomUUID() + "/dose-calculator?mgPerKg=10", HttpMethod.GET, authed(c.token), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        String p = patient(c, c.today().minusYears(4));
        weigh(c, p, 16.0);
        assertThat(exchange("/v1/clinical/patients/" + p + "/dose-calculator", HttpMethod.GET, authed(c.token), Map.class)
                .getStatusCode().value()).isEqualTo(400);
        assertThat(exchange("/v1/clinical/patients/" + p + "/dose-calculator?mgPerKg=abc", HttpMethod.GET, authed(c.token), Map.class)
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void otherTenantCannotCalculateForThisPatient() {
        Ctx a = ctx();
        Ctx b = ctx();
        String p = patient(a, a.today().minusYears(4));
        weigh(a, p, 16.0);
        assertThat(dose(a, p, "10").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dose(b, p, "10").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void roleWithoutClinicalView_isForbidden_and_noToken_isUnauthorized() {
        Ctx c = ctx();
        UUID role = seedRole(c.tenant.id(), "FrontDeskOnly", false, fullGrant("queue"), fullGrant("patients"));
        String tok = loginAndGetAccessToken(seedStaff(c.tenant, role, "fd" + System.nanoTime() + "@a.com", "+9198" + (10000000 + (int) (System.nanoTime() % 80000000)), false));
        assertThat(exchange("/v1/clinical/patients/" + UUID.randomUUID() + "/dose-calculator?mgPerKg=10", HttpMethod.GET, authed(tok), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/clinical/patients/" + UUID.randomUUID() + "/dose-calculator?mgPerKg=10", HttpMethod.GET, null, Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Title says "refuses unsafe inputs": a zero or negative mg/kg must not yield a dose suggestion. */
    @Test
    void zeroRate_isRefused() {
        Ctx c = ctx();
        String p = patient(c, c.today().minusYears(4));
        weigh(c, p, 16.0);
        ResponseEntity<Map> r = dose(c, p, "0");
        assertThat(r.getStatusCode().is4xxClientError()).as("mgPerKg=0 -> %s %s", r.getStatusCode(), r.getBody()).isTrue();
    }

    @Test
    void negativeRate_isRefused() {
        Ctx c = ctx();
        String p = patient(c, c.today().minusYears(4));
        weigh(c, p, 16.0);
        ResponseEntity<Map> r = dose(c, p, "-5");
        assertThat(r.getStatusCode().is4xxClientError()).as("mgPerKg=-5 -> %s %s", r.getStatusCode(), r.getBody()).isTrue();
    }
}
