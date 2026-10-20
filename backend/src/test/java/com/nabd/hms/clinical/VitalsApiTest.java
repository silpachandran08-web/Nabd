package com.nabd.hms.clinical;

import com.nabd.hms.common.GrantsFlattener;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.Hazard;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VitalsApiTest extends ApiTestBase {

    private String registerPatient(String token, String name, String phone) {
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", "1990-01-01", "gender", "male")), Map.class);
        return (String) resp.getBody().get("id");
    }

    /** Minors cannot be registered without a guardian, so register as an adult then set the dob directly. */
    private String registerPatient(String token, String name, String phone, String dob, UUID tenantId) {
        String id = registerPatient(token, name, phone);
        inTenantTx(tenantId, () -> jdbc.update("UPDATE patients SET dob = ?::date WHERE id = ?::uuid", dob, id));
        return id;
    }

    private String checkIn(String token, String patientId, UUID doctorId) {
        ResponseEntity<Map> resp = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", doctorId.toString())), Map.class);
        return (String) resp.getBody().get("id");
    }

    private void moveTo(String token, String queueEntryId, String... statuses) {
        for (String s : statuses) {
            exchange("/v1/queue/" + queueEntryId + "/status", HttpMethod.PATCH,
                    authedJsonBody(token, Map.of("status", s)), Map.class);
        }
    }

    @Test
    void recordingVitalsAtVitalsPendingAdvancesTheQueueToVitalsDone() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "nurse3@a.com", "+919800010001", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "V1", "+919999950001");
        String queueEntryId = checkIn(token, patientId, staff.id());
        moveTo(token, queueEntryId, "waiting", "vitals_pending");

        ResponseEntity<Map> resp = exchange("/v1/clinical/vitals/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "heightCm", 170.5, "weightKg", 68.2, "bpSystolic", 120, "bpDiastolic", 80,
                "pulseBpm", 72, "tempCelsius", 37.0, "spo2Percent", 98)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) resp.getBody().get("bpSystolic")).intValue()).isEqualTo(120);

        ResponseEntity<java.util.List> list = exchange("/v1/queue?doctorId=" + staff.id(), HttpMethod.GET, authed(token), java.util.List.class);
        Map<?, ?> entry = (Map<?, ?>) list.getBody().stream()
                .filter(e -> queueEntryId.equals(((Map<?, ?>) e).get("id"))).findFirst().orElseThrow();
        assertThat(entry.get("status")).isEqualTo("vitals_done");
    }

    @Test
    void doseCalculatorReportsWhenTheWeightWasRecordedAndNotWhenALaterNonWeightSaveHappened() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "nurse5@a.com", "+919800010005", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "V5", "+919999950005", java.time.LocalDate.now().minusYears(5).toString(), tenant.id());
        String queueEntryId = checkIn(token, patientId, staff.id());
        moveTo(token, queueEntryId, "waiting", "vitals_pending");

        // no weight yet: the calculator refuses rather than estimating
        ResponseEntity<Map> none = exchange("/v1/clinical/patients/" + patientId + "/dose-calculator?mgPerKg=10", HttpMethod.GET, authed(token), Map.class);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        exchange("/v1/clinical/vitals/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of("weightKg", 13.4)), Map.class);
        ResponseEntity<Map> first = exchange("/v1/clinical/patients/" + patientId + "/dose-calculator?mgPerKg=10", HttpMethod.GET, authed(token), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        Object weightAt = first.getBody().get("weightRecordedAt");
        assertThat(weightAt).isNotNull();

        // a later pulse-only save must not refresh the weight's timestamp
        exchange("/v1/clinical/vitals/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of("pulseBpm", 90)), Map.class);
        ResponseEntity<Map> second = exchange("/v1/clinical/patients/" + patientId + "/dose-calculator?mgPerKg=10", HttpMethod.GET, authed(token), Map.class);
        assertThat(second.getBody().get("weightRecordedAt")).isEqualTo(weightAt);
        assertThat(second.getBody().get("ruleSource")).isNotNull();
        // weight recorded today: fresh, and the rule source string is unchanged
        assertThat(second.getBody().get("weightStale")).isEqualTo(false);
        assertThat((String) second.getBody().get("ruleSource")).startsWith("Weight-based mg/kg calculation v1");
    }

    private ResponseEntity<Map> dose(String token, String patientId) {
        return exchange("/v1/clinical/patients/" + patientId + "/dose-calculator?mgPerKg=10", HttpMethod.GET, authed(token), Map.class);
    }

    @Test
    @Hazard("H-07")
    void doseCalculatorRefusesAdultsWith422() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "nurse6@a.com", "+919800010006", false);
        String token = loginAndGetAccessToken(staff);
        java.time.LocalDate today = java.time.LocalDate.now();
        String adult = registerPatient(token, "V6", "+919999950006", today.minusYears(18).minusDays(1).toString(), tenant.id());
        ResponseEntity<Map> r = dose(token, adult);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) r.getBody().get("type")).endsWith("adult-patient");
        // 17 years and almost 12 months is still a child: not refused for age (no weight yet -> 400)
        String teen = registerPatient(token, "V6b", "+919999950016", today.minusYears(18).plusDays(2).toString(), tenant.id());
        assertThat(dose(token, teen).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @Hazard("H-07")
    void fourteenMonthOldWithA40DayOldWeightIsFlaggedStale() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "nurse7@a.com", "+919800010007", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "V7", "+919999950007", java.time.LocalDate.now().minusMonths(14).toString(), tenant.id());
        String queueEntryId = checkIn(token, patientId, staff.id());
        moveTo(token, queueEntryId, "waiting", "vitals_pending");
        exchange("/v1/clinical/vitals/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of("weightKg", 10.2)), Map.class);
        assertThat(dose(token, patientId).getBody().get("weightStale")).isEqualTo(false);

        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE vitals SET weight_recorded_at = now() - interval '40 days' WHERE patient_id = ?::uuid", patientId));
        ResponseEntity<Map> r = dose(token, patientId);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("weightStale")).isEqualTo(true);
        assertThat(r.getBody().get("weightRecordedAt")).isNotNull();

        // the same 40-day-old weight is still fresh once the child is past 2 years (90-day window)
        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE patients SET dob = ?::date WHERE id = ?::uuid",
                java.time.LocalDate.now().minusYears(3).toString(), patientId));
        assertThat(dose(token, patientId).getBody().get("weightStale")).isEqualTo(false);
    }

    @Test
    void correctingVitalsAfterVitalsDoneDoesNotAttemptAnIllegalTransition() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "nurse4@a.com", "+919800010002", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "V2", "+919999950002");
        String queueEntryId = checkIn(token, patientId, staff.id());
        moveTo(token, queueEntryId, "waiting", "vitals_pending", "vitals_done");

        ResponseEntity<Map> resp = exchange("/v1/clinical/vitals/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "heightCm", 171.0, "weightKg", 68.0, "bpSystolic", 118, "bpDiastolic", 78,
                "pulseBpm", 70, "tempCelsius", 36.9, "spo2Percent", 99)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) resp.getBody().get("bpSystolic")).intValue()).isEqualTo(118);
    }

    private String pendingVisit(String token, UUID doctorId, String phone) {
        String queueEntryId = checkIn(token, registerPatient(token, "R60", phone), doctorId);
        moveTo(token, queueEntryId, "waiting", "vitals_pending");
        return queueEntryId;
    }

    private ResponseEntity<Map> saveVitals(String token, String queueEntryId, Map<String, Object> body) {
        return exchange("/v1/clinical/vitals/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, body), Map.class);
    }

    @Test
    void createOnlyRoleRecordsFirstVitalsAndMissingFieldsButCannotCorrectARecordedValue() {
        SeededTenant tenant = seedTenant();
        SeededStaff admin = seedStaff(tenant, seedFullAccessRole(tenant.id()), "r60-admin@a.com", "+919800060001", false);
        String adminToken = loginAndGetAccessToken(admin);
        UUID createOnly = seedRole(tenant.id(), "VitalsCreateOnly", false,
                new ModuleGrant("clinical", true, true, false, false, false, false, false));
        String token = loginAndGetAccessToken(seedStaff(tenant, createOnly, "r60-nurse@a.com", "+919800060002", false));
        String queueEntryId = pendingVisit(adminToken, admin.id(), "+919999960001");

        assertThat(saveVitals(token, queueEntryId, Map.of("pulseBpm", 72, "spo2Percent", 98)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // filling a still-empty field and re-sending an unchanged value are not corrections
        assertThat(saveVitals(token, queueEntryId, Map.of("pulseBpm", 72, "tempCelsius", 37.0)).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> correction = saveVitals(token, queueEntryId, Map.of("pulseBpm", 80));
        assertThat(correction.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(correction.getBody().get("type")).asString().contains("vitals-correction-forbidden");
        ResponseEntity<Map> stored = exchange("/v1/clinical/vitals/" + queueEntryId, HttpMethod.GET, authed(token), Map.class);
        assertThat(((Number) stored.getBody().get("pulseBpm")).intValue()).isEqualTo(72);
        assertThat(((Number) stored.getBody().get("tempCelsius")).doubleValue()).isEqualTo(37.0);
    }

    @Test
    void editRoleRecordsAndCorrectsVitals() {
        SeededTenant tenant = seedTenant();
        SeededStaff admin = seedStaff(tenant, seedFullAccessRole(tenant.id()), "r60-admin2@a.com", "+919800060003", false);
        String adminToken = loginAndGetAccessToken(admin);
        UUID editOnly = seedRole(tenant.id(), "VitalsEdit", false,
                new ModuleGrant("clinical", true, false, true, false, false, false, false));
        String token = loginAndGetAccessToken(seedStaff(tenant, editOnly, "r60-nurse2@a.com", "+919800060004", false));
        String queueEntryId = pendingVisit(adminToken, admin.id(), "+919999960002");

        assertThat(saveVitals(token, queueEntryId, Map.of("pulseBpm", 72)).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> corrected = saveVitals(token, queueEntryId, Map.of("pulseBpm", 80));
        assertThat(corrected.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) corrected.getBody().get("pulseBpm")).intValue()).isEqualTo(80);
    }

    @Test
    void clinicalCreateFlagIsInTheSessionPermissionsOnlyWithTheGrant() {
        assertThat(GrantsFlattener.flatten(List.of(new ModuleGrant("clinical", true, true, false, false, false, false, false))))
                .containsExactly("clinical:view", "clinical:create");
        assertThat(GrantsFlattener.flatten(List.of(new ModuleGrant("clinical", true, false, true, false, false, false, false))))
                .containsExactly("clinical:view", "clinical:edit");
    }

    @Test
    void roleWithoutClinicalGrantIsForbidden() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "FrontDeskOnly", false, fullGrant("queue"), fullGrant("patients"));
        SeededStaff staff = seedStaff(tenant, roleId, "reception3@a.com", "+919800010003", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/clinical/vitals/" + UUID.randomUUID(), HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void emptyImplausibleOrInconsistentVitalsAreRejectedAndDoNotAdvanceTheVisit() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "nurse6@a.com", "+919800010006", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "V6", "+919999950006");
        String queueEntryId = checkIn(token, patientId, staff.id());
        moveTo(token, queueEntryId, "waiting", "vitals_pending");
        String url = "/v1/clinical/vitals/" + queueEntryId;

        assertThat(exchange(url, HttpMethod.PATCH, authedJsonBody(token, Map.of()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(url, HttpMethod.PATCH, authedJsonBody(token, Map.of("tempCelsius", 370)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(url, HttpMethod.PATCH, authedJsonBody(token, Map.of("bpSystolic", 80, "bpDiastolic", 90)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(url, HttpMethod.PATCH, authedJsonBody(token, Map.of("bpSystolic", 120)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<java.util.List> list = exchange("/v1/queue?doctorId=" + staff.id(), HttpMethod.GET, authed(token), java.util.List.class);
        Map<?, ?> entry = (Map<?, ?>) list.getBody().stream()
                .filter(e -> queueEntryId.equals(((Map<?, ?>) e).get("id"))).findFirst().orElseThrow();
        assertThat(entry.get("status")).isEqualTo("vitals_pending");
    }

    // V2-008: a null / omitted field keeps the stored value; a partial save must not wipe the other measurements.
    @Test
    void aSecondSaveWithOnlyPulseKeepsTheOtherMeasurementsAndCorrectionsAreAudited() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "nurse-v28@a.com", "+919800010028", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "V28", "+919999950028");
        String queueEntryId = checkIn(token, patientId, staff.id());
        moveTo(token, queueEntryId, "waiting", "vitals_pending");
        String url = "/v1/clinical/vitals/" + queueEntryId;
        exchange(url, HttpMethod.PATCH, authedJsonBody(token, Map.of("heightCm", 170, "weightKg", 68, "bpSystolic", 120,
                "bpDiastolic", 80, "pulseBpm", 72, "tempCelsius", 37.0, "spo2Percent", 98)), Map.class);

        java.util.HashMap<String, Object> pulseOnly = new java.util.HashMap<>();
        pulseOnly.put("pulseBpm", 90);
        pulseOnly.put("heightCm", null);
        pulseOnly.put("bpSystolic", null);
        ResponseEntity<Map> resp = exchange(url, HttpMethod.PATCH, authedJsonBody(token, pulseOnly), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) resp.getBody().get("pulseBpm")).intValue()).isEqualTo(90);
        assertThat(((Number) resp.getBody().get("bpSystolic")).intValue()).isEqualTo(120);
        assertThat(((Number) resp.getBody().get("heightCm")).doubleValue()).isEqualTo(170.0);
        assertThat(((Number) resp.getBody().get("spo2Percent")).intValue()).isEqualTo(98);

        Map<String, Object> audited = inTenantTx(tenant.id(), () -> jdbc.queryForMap(
                "SELECT before, after FROM audit_log WHERE tenant_id = ? AND action = 'vitals.corrected' ORDER BY created_at DESC LIMIT 1",
                tenant.id()));
        assertThat(audited.get("before").toString()).contains("72");
        assertThat(audited.get("after").toString()).contains("90");
    }

    // V1-011: genuine neonatal / shock / hypothermic readings are accepted; impossible ones are still refused.
    @Test
    void neonatalAndCriticalReadingsAreAcceptedButImpossibleOnesAreNot() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "nurse-v11@a.com", "+919800010011", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "V11", "+919999950011");
        String queueEntryId = checkIn(token, patientId, staff.id());
        moveTo(token, queueEntryId, "waiting", "vitals_pending");
        String url = "/v1/clinical/vitals/" + queueEntryId;

        ResponseEntity<Map> critical = exchange(url, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "weightKg", 0.6, "heightCm", 32, "bpSystolic", 35, "bpDiastolic", 20, "spo2Percent", 42, "tempCelsius", 28.5)), Map.class);
        assertThat(critical.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> impossible = exchange(url, HttpMethod.PATCH, authedJsonBody(token, Map.of("spo2Percent", 0)), Map.class);
        assertThat(impossible.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(impossible.getBody().get("detail"))).contains("spo2Percent");
    }

    // NUR-021: blood sugar + pain score are stored, validated (pain 0-10) and corrections of them are audited.
    @Test
    void bloodSugarAndPainScoreAreStoredValidatedAndCorrectionsAudited() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "nurse-v21@a.com", "+919800010021", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "V21", "+919999950021");
        String queueEntryId = checkIn(token, patientId, staff.id());
        moveTo(token, queueEntryId, "waiting", "vitals_pending");
        String url = "/v1/clinical/vitals/" + queueEntryId;

        assertThat(exchange(url, HttpMethod.PATCH, authedJsonBody(token, Map.of("painScore", 11)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(url, HttpMethod.PATCH, authedJsonBody(token, Map.of("painScore", -1)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(url, HttpMethod.PATCH, authedJsonBody(token, Map.of("bloodSugar", 0)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // pain 0 is a real answer, and blood sugar / pain alone count as a measurement
        ResponseEntity<Map> first = exchange(url, HttpMethod.PATCH, authedJsonBody(token, Map.of("bloodSugar", 5.4, "painScore", 0)), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) first.getBody().get("bloodSugar")).doubleValue()).isEqualTo(5.4);
        assertThat(((Number) first.getBody().get("painScore")).intValue()).isEqualTo(0);

        // a pulse-only save keeps them; a new value corrects and is audited
        ResponseEntity<Map> keep = exchange(url, HttpMethod.PATCH, authedJsonBody(token, Map.of("pulseBpm", 80)), Map.class);
        assertThat(((Number) keep.getBody().get("painScore")).intValue()).isEqualTo(0);
        ResponseEntity<Map> fix = exchange(url, HttpMethod.PATCH, authedJsonBody(token, Map.of("painScore", 7)), Map.class);
        assertThat(((Number) fix.getBody().get("painScore")).intValue()).isEqualTo(7);

        Map<String, Object> audited = inTenantTx(tenant.id(), () -> jdbc.queryForMap(
                "SELECT before, after FROM audit_log WHERE tenant_id = ? AND action = 'vitals.corrected' ORDER BY created_at DESC LIMIT 1",
                tenant.id()));
        assertThat(audited.get("before").toString()).contains("painScore");
        assertThat(audited.get("after").toString()).contains("painScore").contains("7");
    }

    // NUR-022: previous vitals across visits, newest first, limit default 5 / max 20, tenant-scoped, same read permission.
    @Test
    void previousVitalsListsThePatientsVisitsNewestFirstWithLimitAndIsTenantScoped() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "nurse-v22@a.com", "+919800010022", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "V22", "+919999950022");
        String empty = "/v1/clinical/vitals/patient/" + patientId + "/previous";
        assertThat(((java.util.List<?>) exchange(empty, HttpMethod.GET, authed(token), Map.class).getBody().get("items"))).isEmpty();

        java.util.List<String> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String q = checkIn(token, patientId, staff.id());
            moveTo(token, q, "waiting", "vitals_pending");
            exchange("/v1/clinical/vitals/" + q, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                    "bpSystolic", 120 + i, "bpDiastolic", 80, "bloodSugar", 5 + i, "painScore", i)), Map.class);
            moveTo(token, q, "in_consult", "checkout_pending", "completed");
            entries.add(q);
        }
        ResponseEntity<Map> all = exchange(empty, HttpMethod.GET, authed(token), Map.class);
        assertThat(all.getStatusCode()).isEqualTo(HttpStatus.OK);
        java.util.List<Map<String, Object>> items = (java.util.List<Map<String, Object>>) all.getBody().get("items");
        assertThat(items).hasSize(3);
        assertThat(items.get(0).get("queueEntryId")).isEqualTo(entries.get(2));
        assertThat(items.get(2).get("queueEntryId")).isEqualTo(entries.get(0));
        assertThat(items.get(0)).containsKeys("recordedAt", "bpSystolic", "bpDiastolic", "bloodSugar", "painScore");
        assertThat(((Number) items.get(0).get("painScore")).intValue()).isEqualTo(2);

        ResponseEntity<Map> one = exchange(empty + "?limit=1", HttpMethod.GET, authed(token), Map.class);
        assertThat(((java.util.List<?>) one.getBody().get("items"))).hasSize(1);
        assertThat(exchange(empty + "?limit=999", HttpMethod.GET, authed(token), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        // another tenant cannot see this patient's vitals (404, not an empty list that confirms nothing leaks)
        SeededTenant other = seedTenant();
        UUID otherRole = seedFullAccessRole(other.id());
        SeededStaff otherStaff = seedStaff(other, otherRole, "nurse-v22b@b.com", "+919800010023", false);
        String otherToken = loginAndGetAccessToken(otherStaff);
        assertThat(exchange(empty, HttpMethod.GET, authed(otherToken), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // no clinical grant -> 403
        UUID noClinical = seedRole(tenant.id(), "FrontDeskOnly22", false, fullGrant("queue"), fullGrant("patients"));
        SeededStaff desk = seedStaff(tenant, noClinical, "desk-v22@a.com", "+919800010024", false);
        assertThat(exchange(empty, HttpMethod.GET, authed(loginAndGetAccessToken(desk)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
