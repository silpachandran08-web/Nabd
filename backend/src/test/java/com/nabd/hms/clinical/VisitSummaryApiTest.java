package com.nabd.hms.clinical;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** CLN-008: GET /v1/clinical/visits/{queueEntryId}/summary. */
class VisitSummaryApiTest extends ApiTestBase {

    // Signing a prescription needs a registered clinician; every staff member seeded here is one.
    @Override
    protected SeededStaff seedStaff(SeededTenant tenant, UUID roleId, String email, String mobilePhone, boolean mfaEnabled,
                                    boolean emailVerified, boolean mobileVerified) {
        return com.nabd.hms.support.ClinicianFixture.licensed(
                super.seedStaff(tenant, roleId, email, mobilePhone, mfaEnabled, emailVerified, mobileVerified));
    }

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    private String registerPatient(String token, String name, String phone, String gender) {
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", "1990-01-01", "gender", gender)), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
        return (String) r.getBody().get("id");
    }

    private String checkIn(String token, String patientId, UUID doctorId, String complaint) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("patientId", patientId, "doctorId", doctorId.toString()));
        if (complaint != null) {
            body.put("presentingComplaint", complaint);
        }
        return (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, body), Map.class).getBody().get("id");
    }

    private void moveTo(String token, String q, String... statuses) {
        for (String s : statuses) {
            exchange("/v1/queue/" + q + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", s)), Map.class);
        }
    }

    /** One finished visit: note with a diagnosis, optionally a signed prescription, then checkout. */
    private String completedVisit(String token, String patientId, UUID doctorId, String diagnosis, String drug) {
        String q = checkIn(token, patientId, doctorId, null);
        exchange("/v1/clinical/notes/" + q, HttpMethod.PATCH, authedJsonBody(token, Map.of("diagnosis", diagnosis)), Map.class);
        if (drug != null) {
            exchange("/v1/clinical/prescriptions/" + q, HttpMethod.PATCH, authedJsonBody(token, Map.of("items", List.of(Map.of("drugName", drug)))), Map.class);
            exchange("/v1/clinical/prescriptions/" + q + "/sign", HttpMethod.POST, authed(token), Map.class);
        }
        moveTo(token, q, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        exchange("/v1/billing/checkout/" + q, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("override", Map.of("reasonCode", "other"), "chargeCode", "X", "chargeName", "X", "category", "Service",
                        "quantity", 1, "unitPrice", 100, "taxRatePercent", 0)))), Map.class);
        return q;
    }

    private ResponseEntity<Map> summary(String token, String q) {
        return exchange("/v1/clinical/visits/" + q + "/summary", HttpMethod.GET, authed(token), Map.class);
    }

    @Test
    void aPatientWithTwentyPriorVisitsGetsOnlyTheLastThreeInUnder30KbAndUnder300MsP95() {
        SeededTenant tenant = seedTenant();
        SeededStaff doc = seedStaff(tenant, seedFullAccessRole(tenant.id()), "vs1@a.com", "+919820000001", false);
        String token = loginAndGetAccessToken(doc);
        String p = registerPatient(token, "Summary Sam", "+919991100001", "male");
        for (int i = 1; i <= 20; i++) {
            completedVisit(token, p, doc.id(), "Visit " + i, i % 5 == 0 ? "Drug" + i : null);
        }
        exchange("/v1/clinical/patients/" + p + "/conditions", HttpMethod.POST, authedJsonBody(token, Map.of("condition", "Asthma")), Map.class);
        String today = checkIn(token, p, doc.id(), "Cough for 3 days");
        moveTo(token, today, "waiting", "vitals_pending");
        exchange("/v1/clinical/vitals/" + today, HttpMethod.PATCH, authedJsonBody(token, Map.of("pulseBpm", 72, "tempCelsius", 37.0)), Map.class);

        ResponseEntity<String> raw = exchange("/v1/clinical/visits/" + today + "/summary", HttpMethod.GET, authed(token), String.class);
        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(raw.getBody().getBytes().length).as("response size").isLessThan(30 * 1024);

        Map<?, ?> body = summary(token, today).getBody();
        List<?> last = (List<?>) body.get("lastVisits");
        assertThat(last).hasSize(3);
        assertThat(((Map<?, ?>) last.get(0)).get("diagnosis")).isEqualTo("Visit 20");
        assertThat(((Map<?, ?>) last.get(2)).get("diagnosis")).isEqualTo("Visit 18");
        // meds come from the last 3 signed prescriptions only (visits 20, 15, 10), de-duplicated
        List<?> meds = (List<?>) body.get("activeMeds");
        assertThat(meds.stream().<Object>map(m -> ((Map<?, ?>) m).get("drugName")).toList()).containsExactly("Drug20", "Drug15", "Drug10");
        assertThat(((Map<?, ?>) ((List<?>) body.get("conditions")).get(0)).get("condition")).isEqualTo("Asthma");
        assertThat(((Map<?, ?>) body.get("vitals")).get("pulseBpm")).isEqualTo(72);
        assertThat(((Map<?, ?>) body.get("intake")).get("presentingComplaint")).isEqualTo("Cough for 3 days");
        assertThat(body.get("attachmentsCount")).isEqualTo(0);
        Map<?, ?> banner = (Map<?, ?>) body.get("patient");
        assertThat(banner.get("name")).isEqualTo("Summary Sam");
        assertThat(banner.get("ageText")).asString().endsWith(" y");

        List<Long> ms = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            long t0 = System.nanoTime();
            assertThat(summary(token, today).getStatusCode()).isEqualTo(HttpStatus.OK);
            ms.add((System.nanoTime() - t0) / 1_000_000);
        }
        Collections.sort(ms);
        long p95 = ms.get((int) Math.ceil(ms.size() * 0.95) - 1);
        System.out.println("CLN-008 summary p95=" + p95 + " ms, median=" + ms.get(ms.size() / 2) + " ms, bytes=" + raw.getBody().getBytes().length);
        assertThat(p95).as("p95 ms over 30 calls (HTTP round trip incl. auth)").isLessThan(300);
    }

    @Test
    void safetyStatesAreNeverInventedAndRecordedOnesAreListed() {
        SeededTenant tenant = seedTenant();
        SeededStaff doc = seedStaff(tenant, seedFullAccessRole(tenant.id()), "vs2@a.com", "+919820000002", false);
        String token = loginAndGetAccessToken(doc);
        String p = registerPatient(token, "Safety Sana", "+919991100002", "female");
        String q = checkIn(token, p, doc.id(), null);

        Map<?, ?> fresh = (Map<?, ?>) summary(token, q).getBody().get("safety");
        assertThat(fresh.get("allergyState")).isEqualTo("not_assessed"); // nobody asked: NOT "none"
        assertThat(((List<?>) fresh.get("allergies"))).isEmpty();
        assertThat(((Map<?, ?>) fresh.get("pregnancy")).get("status")).isEqualTo("not_documented");

        exchange("/v1/clinical/patients/" + p + "/allergies", HttpMethod.POST, authedJsonBody(token, Map.of("substance", "Penicillin", "severity", "severe")), Map.class);
        exchange("/v1/clinical/patients/" + p + "/pregnancy-status", HttpMethod.PUT, authedJsonBody(token, Map.of("status", "pregnant",
                "expectedDeliveryDate", LocalDate.now().plusMonths(3).toString())), Map.class);
        Map<?, ?> safety = (Map<?, ?>) summary(token, q).getBody().get("safety");
        assertThat(safety.get("allergyState")).isEqualTo("recorded");
        Map<?, ?> a = (Map<?, ?>) ((List<?>) safety.get("allergies")).get(0);
        assertThat(a.get("substance")).isEqualTo("Penicillin");
        assertThat(a.get("severity")).isEqualTo("severe");
        assertThat(((Map<?, ?>) safety.get("pregnancy")).get("status")).isEqualTo("pregnant");
    }

    @Test
    void permissionTenantAndScopeGates() {
        SeededTenant tenant = seedTenant();
        UUID full = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, full, "vs3@a.com", "+919820000003", false);
        SeededStaff other = seedStaff(tenant, full, "vs3b@a.com", "+919820000004", false);
        String token = loginAndGetAccessToken(doc);
        String p = registerPatient(token, "Gate Gita", "+919991100003", "female");
        String q = checkIn(token, p, doc.id(), null);

        // no clinical:view -> 403
        UUID noClinical = seedRole(tenant.id(), "queue-only", false, new com.nabd.hms.common.ModuleGrant("queue", true, false, false, false, false, false, false));
        SeededStaff clerk = seedStaff(tenant, noClinical, "vs3c@a.com", "+919820000005", false);
        assertThat(summary(loginAndGetAccessToken(clerk), q).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // another clinic's doctor and an unknown id: the same 404 problem, no data
        SeededTenant tenant2 = seedTenant();
        SeededStaff foreign = seedStaff(tenant2, seedFullAccessRole(tenant2.id()), "vs3d@a.com", "+919820000006", false);
        ResponseEntity<Map> cross = summary(loginAndGetAccessToken(foreign), q);
        assertThat(cross.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(String.valueOf(cross.getBody())).doesNotContain("Gate Gita");
        assertThat(summary(token, UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // own_patients_only doctor who has no relation to the patient: 404; the visit's own doctor: 200
        su.update("UPDATE staff SET scope = 'own_patients_only' WHERE id = ?", other.id());
        assertThat(summary(loginAndGetAccessToken(other), q).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        su.update("UPDATE staff SET scope = 'own_patients_only' WHERE id = ?", doc.id());
        assertThat(summary(loginAndGetAccessToken(doc), q).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theReadIsLoggedInPhiReadLogAndAFieldGrantHidesTheDiagnosis() {
        SeededTenant tenant = seedTenant();
        UUID full = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, full, "vs4@a.com", "+919820000007", false);
        String token = loginAndGetAccessToken(doc);
        String p = registerPatient(token, "Audit Asha", "+919991100004", "female");
        completedVisit(token, p, doc.id(), "Secret Dx", null);
        String q = checkIn(token, p, doc.id(), null);

        assertThat(summary(token, q).getStatusCode()).isEqualTo(HttpStatus.OK);
        Integer logged = su.queryForObject("SELECT count(*) FROM phi_read_log WHERE tenant_id = ? AND patient_id = ?::uuid AND resource_type = 'visit_summary'",
                Integer.class, tenant.id(), p);
        assertThat(logged).isGreaterThanOrEqualTo(1);

        su.update("UPDATE staff SET field_grants = ARRAY['vitals'] WHERE id = ?", doc.id());
        Map<?, ?> v = (Map<?, ?>) ((List<?>) summary(loginAndGetAccessToken(doc), q).getBody().get("lastVisits")).get(0);
        assertThat(v.get("diagnosis")).isNull();
    }

    @Test
    void ageTextIsShortAndHonest() {
        LocalDate today = LocalDate.of(2026, 10, 27);
        assertThat(VisitSummaryService.ageText(LocalDate.of(1985, 3, 1), today)).isEqualTo("41 y");
        assertThat(VisitSummaryService.ageText(LocalDate.of(2025, 2, 27), today)).isEqualTo("20 m"); // under 2 years: months
        assertThat(VisitSummaryService.ageText(today.minusDays(12), today)).isEqualTo("12 d");
        assertThat(VisitSummaryService.ageText(null, today)).isEmpty();
        assertThat(VisitSummaryService.ageText(today.plusDays(1), today)).isEmpty();
    }

    @Test
    void theMedicinesQueryUsesTheSummaryIndexWhenAScanIsPossible() {
        SeededTenant tenant = seedTenant();
        SeededStaff doc = seedStaff(tenant, seedFullAccessRole(tenant.id()), "vs5@a.com", "+919820000008", false);
        String token = loginAndGetAccessToken(doc);
        String p = registerPatient(token, "Plan Pia", "+919991100005", "female");
        completedVisit(token, p, doc.id(), "Dx", "PlanDrug");
        // a handful of rows only: force index use so the test proves the index covers the lookup and is chosen over a sort
        List<String> plan = inTenantTx(tenant.id(), () -> {
            jdbc.execute("SET LOCAL jit = off");
            jdbc.execute("SET LOCAL enable_seqscan = off");
            return jdbc.queryForList("EXPLAIN SELECT i.drug_name FROM prescription_items i JOIN (SELECT id, signed_at FROM prescriptions "
                    + "WHERE tenant_id = ?::uuid AND patient_id = ?::uuid AND status = 'signed' ORDER BY signed_at DESC LIMIT 3) r ON r.id = i.prescription_id "
                    + "WHERE i.tenant_id = ?::uuid ORDER BY r.signed_at DESC, i.display_order LIMIT 60", String.class, tenant.id().toString(), p, tenant.id().toString());
        });
        assertThat(String.join("\n", plan)).contains("idx_prescription_items_summary");
    }
}
