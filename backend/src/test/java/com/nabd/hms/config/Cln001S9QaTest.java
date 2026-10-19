package com.nabd.hms.config;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA (S9) for CLN-001: CORS preflight for PUT, and the pregnancy / NKA writes behind it. */
class Cln001S9QaTest extends ApiTestBase {

    // CLN-005: signing needs a registered clinician. Every staff member these tests seed is one, so they keep testing what
    // they were written for; the licence refusals themselves are covered in ClinicalLicenceApiTest.
    @Override
    protected SeededStaff seedStaff(SeededTenant tenant, UUID roleId, String email, String mobilePhone, boolean mfaEnabled,
                                    boolean emailVerified, boolean mobileVerified) {
        return com.nabd.hms.support.ClinicianFixture.licensed(
                super.seedStaff(tenant, roleId, email, mobilePhone, mfaEnabled, emailVerified, mobileVerified));
    }

    private static final String STAFF = "http://localhost:3000";
    private static final AtomicInteger SEQ = new AtomicInteger(5000);
    private static final String ANY_ID = "00000000-0000-0000-0000-000000000009";

    private ResponseEntity<String> preflight(String origin, String method, String path, String reqHeaders) {
        HttpHeaders h = new HttpHeaders();
        if (origin != null) h.set("Origin", origin);
        h.set("Access-Control-Request-Method", method);
        if (reqHeaders != null) h.set("Access-Control-Request-Headers", reqHeaders);
        return http.exchange(url(path), HttpMethod.OPTIONS, new HttpEntity<>(h), String.class);
    }

    // ---- AC1 ----
    @Test
    void ac1_putPreflightOnBothSafetyEndpointsIs200WithPutAndMaxAge600() {
        for (String p : List.of("/v1/clinical/patients/" + ANY_ID + "/pregnancy-status", "/v1/clinical/patients/" + ANY_ID + "/allergies/status")) {
            ResponseEntity<String> r = preflight(STAFF, "PUT", p, "authorization,content-type");
            assertThat(r.getStatusCode()).as(p).isEqualTo(HttpStatus.OK);
            assertThat(r.getHeaders().getFirst("Access-Control-Allow-Methods")).contains("PUT");
            assertThat(r.getHeaders().getFirst("Access-Control-Allow-Origin")).isEqualTo(STAFF);
            assertThat(r.getHeaders().getFirst("Access-Control-Max-Age")).isEqualTo("600");
            assertThat(r.getHeaders().getFirst("Access-Control-Allow-Headers").toLowerCase()).contains("authorization").contains("content-type");
        }
    }

    @Test
    void ac1_everyVerbOnSeveralPathsIncludingAuthAndPublicOnes() {
        for (String p : List.of("/v1/queue/check-in", "/v1/doctors/" + ANY_ID + "/working-hours", "/v1/auth/login", "/v1/patients")) {
            for (String verb : List.of("GET", "POST", "PUT", "PATCH", "DELETE")) {
                ResponseEntity<String> r = preflight(STAFF, verb, p, "authorization,content-type,x-request-id");
                assertThat(r.getStatusCode()).as(verb + " " + p).isEqualTo(HttpStatus.OK);
                assertThat(r.getHeaders().getFirst("Access-Control-Allow-Methods")).as(verb + " " + p).contains(verb);
            }
        }
    }

    // ---- AC5 ----
    @Test
    void ac5_nonAllowListedOriginsAreRejected_noWildcardNoSuffixOrPortTricks() {
        for (String origin : List.of("https://evil.example.com", "http://localhost:3001", "http://localhost", "https://localhost:3000",
                "http://localhost:3000.evil.com", "http://evil.com/http://localhost:3000", "null", "http://LOCALHOST:3000x")) {
            for (String verb : List.of("PUT", "POST", "DELETE")) {
                ResponseEntity<String> r = preflight(origin, verb, "/v1/clinical/patients/" + ANY_ID + "/pregnancy-status", "authorization,content-type");
                // an origin whose "port" is not numeric is not parsed as CORS by Spring: it gets no CORS headers (401), which a browser also blocks
                if (origin.equals("http://localhost:3000.evil.com")) assertThat(r.getStatusCode().value()).as(origin).isIn(401, 403);
                else assertThat(r.getStatusCode()).as(origin + " " + verb).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(r.getHeaders().getFirst("Access-Control-Allow-Origin")).as(origin).isNull();
                assertThat(r.getHeaders().getFirst("Access-Control-Allow-Methods")).as(origin).isNull();
            }
        }
    }

    @Test
    void ac5_allowOriginIsNeverWildcardAndMethodsOutsideTheListAreRefused() {
        ResponseEntity<String> ok = preflight(STAFF, "PUT", "/v1/patients", "authorization");
        assertThat(ok.getHeaders().getFirst("Access-Control-Allow-Origin")).isNotEqualTo("*");
        assertThat(ok.getHeaders().getFirst("Access-Control-Allow-Methods")).doesNotContain("*").doesNotContain("TRACE").doesNotContain("CONNECT");
        for (String verb : List.of("TRACE", "CONNECT", "PROPFIND")) {
            assertThat(preflight(STAFF, verb, "/v1/patients", "authorization").getStatusCode()).as(verb).isEqualTo(HttpStatus.FORBIDDEN);
        }
        // a header that is not on the allow-list is refused too
        assertThat(preflight(STAFF, "PUT", "/v1/patients", "x-evil-header").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void ac5_actualPutFromForeignOriginIsRefusedEvenWithAValidToken() {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "cors" + SEQ.incrementAndGet() + "@a.com", "+9193" + (1000000 + SEQ.get()), false);
        String token = loginAndGetAccessToken(s);
        String patient = patient(token, "female", "1995-01-01");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        h.set("Origin", "https://evil.example.com");
        ResponseEntity<String> r = http.exchange(url("/v1/clinical/patients/" + patient + "/pregnancy-status"), HttpMethod.PUT,
                new HttpEntity<>("{\"status\":\"pregnant\"}", h), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/clinical/patients/" + patient + "/pregnancy-status", HttpMethod.GET, authed(token), Map.class)
                .getBody().get("status")).isEqualTo("not_documented");
    }

    // ---- AC2 ----
    private String patient(String token, String gender, String dob) {
        int n = SEQ.incrementAndGet();
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of("name", java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10) + " Qz",
                "phone", "+9196" + (1000000 + n), "dob", dob.substring(0, 5) + String.format("%02d-%02d", 1 + n % 12, 1 + n % 27), "gender", gender)), Map.class);
        assertThat(r.getStatusCode()).as("patient create: " + r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private record U(SeededTenant tenant, SeededStaff staff, String token) { }

    private U user() {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "u" + SEQ.incrementAndGet() + "@a.com", "+9192" + (1000000 + SEQ.get()), false);
        return new U(t, s, loginAndGetAccessToken(s));
    }

    @Test
    void ac2_putPregnantFromStaffOriginReturns200WritesEventAndIsAudited() {
        U u = user();
        String p = patient(u.token, "female", "1998-03-04");
        String path = "/v1/clinical/patients/" + p + "/pregnancy-status";
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(u.token);
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        h.set("Origin", STAFF); // as the browser would send it
        long t0 = System.nanoTime();
        ResponseEntity<Map> r = http.exchange(url(path), HttpMethod.PUT, new HttpEntity<>("{\"status\":\"pregnant\",\"expectedDeliveryDate\":\"2027-02-01\"}", h), Map.class);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getFirst("Access-Control-Allow-Origin")).isEqualTo(STAFF);
        assertThat(r.getBody()).containsKeys("status", "expectedDeliveryDate", "recordedBy", "recordedAt");
        assertThat(r.getBody().get("status")).isEqualTo("pregnant");
        assertThat(ms).as("PUT latency (NFR p95<200ms, single warm-ish call)").isLessThan(2000);
        assertThat(inTenantTx(u.tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM patient_pregnancy_events WHERE patient_id = ?::uuid", Integer.class, p))).isEqualTo(1);
        assertThat(exchange(path, HttpMethod.GET, authed(u.token), Map.class).getBody().get("status")).isEqualTo("pregnant");
    }

    @Test
    void pregnancyValidationPermissionAndIsolation() {
        U u = user();
        U other = user();
        String p = patient(u.token, "female", "1998-03-04");
        String path = "/v1/clinical/patients/" + p + "/pregnancy-status";
        // EDD only when pregnant
        assertThat(exchange(path, HttpMethod.PUT, authedJsonBody(u.token, Map.of("status", "not_pregnant", "expectedDeliveryDate", "2027-02-01")), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(path, HttpMethod.PUT, authedJsonBody(u.token, Map.of("status", "bogus")), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(path, HttpMethod.PUT, authedJsonBody(u.token, Map.of()), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // unknown patient 404; other tenant's patient 404 and nothing written
        assertThat(exchange("/v1/clinical/patients/" + UUID.randomUUID() + "/pregnancy-status", HttpMethod.PUT, authedJsonBody(u.token, Map.of("status", "pregnant")), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(path, HttpMethod.PUT, authedJsonBody(other.token, Map.of("status", "pregnant")), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(inTenantTx(u.tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM patient_pregnancy_events", Integer.class))).isZero();
        // no clinical:edit -> 403
        UUID viewRole = seedRole(u.tenant.id(), "ClinView", false, new com.nabd.hms.common.ModuleGrant("clinical", true, false, false, false, false, false, false), fullGrant("patients"));
        SeededStaff v = seedStaff(u.tenant, viewRole, "v" + SEQ.incrementAndGet() + "@a.com", "+9191" + (1000000 + SEQ.get()), false);
        String vt = loginAndGetAccessToken(v);
        assertThat(exchange(path, HttpMethod.PUT, authedJsonBody(vt, Map.of("status", "pregnant")), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/clinical/patients/" + p + "/allergies/status", HttpMethod.PUT, authed(vt), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(path, HttpMethod.PUT, jsonBody(Map.of("status", "pregnant")), Map.class).getStatusCode().value()).isIn(401, 403);
        // idempotent
        assertThat(exchange(path, HttpMethod.PUT, authedJsonBody(u.token, Map.of("status", "lactating")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange(path, HttpMethod.PUT, authedJsonBody(u.token, Map.of("status", "lactating")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(inTenantTx(u.tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM patient_pregnancy_events", Integer.class))).isEqualTo(1);
    }

    // ---- AC3 ----
    @Test
    void ac3_noKnownAllergiesPersistsAssessmentNamesWhoAndRefusesWhenAllergiesExist() {
        U u = user();
        String p = patient(u.token, "female", "1990-01-01");
        String path = "/v1/clinical/patients/" + p + "/allergies/status";
        assertThat(exchange(path, HttpMethod.GET, authed(u.token), Map.class).getBody().get("state")).isEqualTo("not_assessed");
        // body as in the story spec must be accepted too
        ResponseEntity<Map> put = exchange(path, HttpMethod.PUT, authedJsonBody(u.token, Map.of("status", "no_known_allergies")), Map.class);
        assertThat(put.getStatusCode()).as("" + put.getBody()).isEqualTo(HttpStatus.OK);
        String dbStatus = inTenantTx(u.tenant.id(), () -> jdbc.queryForObject("SELECT status FROM patient_allergy_assessments WHERE patient_id = ?::uuid", String.class, p));
        assertThat(dbStatus).isEqualTo("no_known_allergies");
        Map st = exchange(path, HttpMethod.GET, authed(u.token), Map.class).getBody();
        assertThat(st.get("state")).isEqualTo("none_documented");
        assertThat(st.get("documentedByName")).isEqualTo("Test Staff");
        assertThat(st.get("documentedAt")).isNotNull();
        // repeat is a 200 no-op
        assertThat(exchange(path, HttpMethod.PUT, authed(u.token), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        // cross-tenant 404
        U other = user();
        assertThat(exchange(path, HttpMethod.PUT, authed(other.token), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // adding an allergy flips state to recorded; NKA now refused with 409
        exchange("/v1/clinical/patients/" + p + "/allergies", HttpMethod.POST, authedJsonBody(u.token, Map.of("substance", "Penicillin", "severity", "severe")), Map.class);
        assertThat(exchange(path, HttpMethod.GET, authed(u.token), Map.class).getBody().get("state")).isEqualTo("recorded");
        String p2 = patient(u.token, "male", "1990-01-01");
        exchange("/v1/clinical/patients/" + p2 + "/allergies", HttpMethod.POST, authedJsonBody(u.token, Map.of("substance", "Dust", "severity", "mild")), Map.class);
        ResponseEntity<Map> refused = exchange("/v1/clinical/patients/" + p2 + "/allergies/status", HttpMethod.PUT, authed(u.token), Map.class);
        assertThat(refused.getStatusCode()).as("p2=" + p2 + " " + refused.getBody()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ---- AC4 (API half of J7) ----
    @Test
    void ac4_afterRecordingPregnantAnIsotretinoinLineRequiresADecisionAndBlocksSigning() {
        U u = user();
        String p = patient(u.token, "female", "1998-03-04");
        String q = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(u.token, Map.of("patientId", p, "doctorId", u.staff.id().toString())), Map.class).getBody().get("id");
        assertThat(exchange("/v1/clinical/patients/" + p + "/pregnancy-status", HttpMethod.PUT, authedJsonBody(u.token, Map.of("status", "pregnant")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        String rx = "/v1/clinical/prescriptions/" + q;
        long t0 = System.nanoTime();
        ResponseEntity<Map> draft = exchange(rx, HttpMethod.PATCH, authedJsonBody(u.token, Map.of("items", List.of(Map.of("drugName", "Isotretinoin 20mg", "dosage", "20mg")))), Map.class);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(draft.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map> w = (List<Map>) draft.getBody().get("warnings");
        Map preg = null; // flat list has no requiresDecision; read it from the line's own warnings
        Map line = ((List<Map>) draft.getBody().get("items")).get(0);
        preg = ((List<Map>) line.get("warnings")).stream().filter(m -> "pregnancy".equals(m.get("kind"))).findFirst().orElseThrow();
        assertThat(w.stream().anyMatch(m -> "pregnancy".equals(m.get("kind")) && "severe".equals(m.get("severity")))).isTrue();
        assertThat(preg.get("requiresDecision")).isEqualTo(true);
        assertThat(preg.get("severity")).isEqualTo("severe");
        assertThat(ms).isLessThan(10_000);
        assertThat(exchange(rx + "/sign", HttpMethod.POST, authed(u.token), Map.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void ac4_recordedPregnantEscalatesEvenForAnOlderOrMalePatientRecordButNotNotPregnant() {
        U u = user();
        String old = patient(u.token, "female", "1960-01-01"); // outside childbearing age: no default caution
        String q = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(u.token, Map.of("patientId", old, "doctorId", u.staff.id().toString())), Map.class).getBody().get("id");
        String rx = "/v1/clinical/prescriptions/" + q;
        Map<String, Object> body = Map.of("items", List.of(Map.of("drugName", "Isotretinoin", "dosage", "20mg")));
        List<Map> before = (List<Map>) exchange(rx, HttpMethod.PATCH, authedJsonBody(u.token, body), Map.class).getBody().get("warnings");
        assertThat(before.stream().noneMatch(m -> "pregnancy".equals(m.get("kind")))).isTrue();
        exchange("/v1/clinical/patients/" + old + "/pregnancy-status", HttpMethod.PUT, authedJsonBody(u.token, Map.of("status", "pregnant")), Map.class);
        Map afterBody = exchange(rx, HttpMethod.PATCH, authedJsonBody(u.token, body), Map.class).getBody();
        List<Map> after = (List<Map>) ((Map) ((List) afterBody.get("items")).get(0)).get("warnings");
        assertThat(after.stream().anyMatch(m -> "pregnancy".equals(m.get("kind")) && Boolean.TRUE.equals(m.get("requiresDecision")))).as("" + afterBody).isTrue();
    }
}
