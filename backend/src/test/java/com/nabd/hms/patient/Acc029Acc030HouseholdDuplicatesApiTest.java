package com.nabd.hms.patient;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-029 household lookup and ACC-030 duplicate suggestions, dismissal, merge audit and the 30-day undo window. */
class Acc029Acc030HouseholdDuplicatesApiTest extends ApiTestBase {

    private static final String N1 = "Abdulrahman Mohammed";
    private static final String N2 = "Abdulrahman Mohamed"; // trigram similarity 0.86 with N1

    private String owner(SeededTenant t, String email, String mobile) {
        UUID role = seedRole(t.id(), "Reception " + UUID.randomUUID().toString().substring(0, 6), false,
                new ModuleGrant("patients", true, true, true, true, false, false, false), new ModuleGrant("queue", true, true, true, false, false, false, false));
        return mfaLogin(t, seedStaff(t, role, email, mobile, true));
    }

    private String mfaLogin(SeededTenant t, SeededStaff staff) {
        ResponseEntity<Map> login = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", t.slug(), "email", staff.email(), "pin", STAFF_PIN)), Map.class);
        ResponseEntity<Map> mfa = http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of(
                "challengeId", login.getBody().get("challengeId"), "code", currentTotpCode())), Map.class);
        return (String) mfa.getBody().get("accessToken");
    }

    private String stepUp(SeededTenant t, String token) {
        inTenantTx(t.id(), () -> jdbc.update("UPDATE staff SET mfa_last_used_step = NULL WHERE tenant_id = ?", t.id())); // PLT-003 replay guard
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> r = http.postForEntity(url("/v1/auth/mfa/verify"),
                new HttpEntity<>("{\"challengeId\":\"unused\",\"code\":\"" + currentTotpCode() + "\"}", h), Map.class);
        return (String) r.getBody().get("stepUpToken");
    }

    private String register(String token, String name, String phone, String dob, UUID guardian) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("name", name, "phone", phone, "dob", dob, "gender", "female", "confirmedNotDuplicate", true));
        if (guardian != null) {
            body.put("guardianId", guardian.toString());
            body.put("guardianConsent", Map.of("confirmed", true, "channel", "in_person"));
        }
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, body), Map.class);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private ResponseEntity<Map> get(String token, String path) {
        return exchange(path, HttpMethod.GET, authed(token), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> household(String token, String phone) {
        ResponseEntity<Map> r = get(token, "/v1/patients/by-phone?phone=" + phone);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) r.getBody().get("household");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> suggestions(String token) {
        List<Map<String, Object>> all = new ArrayList<>();
        String cursor = null;
        do {
            ResponseEntity<Map> r = get(token, "/v1/patients/duplicate-suggestions" + (cursor == null ? "" : "?cursor=" + cursor));
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
            all.addAll((List<Map<String, Object>>) r.getBody().get("items"));
            cursor = (String) r.getBody().get("nextCursor");
        } while (cursor != null);
        return all;
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.nabd.hms.common.ClinicClock clinicClock;

    // ---------------------------------------------------------------- ACC-029

    @Test
    void phoneSharedByMotherAndChildrenListsThreeRowsWithRelationAgeAndLastVisit() {
        SeededTenant t = seedTenant();
        String token = owner(t, "h1@a.com", "+919300000101");
        String mother = register(token, "Mona Said", "+919877700001", "1988-04-02", null);
        String kid1 = register(token, "Kid One", "+919877700001", clinicClock.today(t.id()).minusYears(6).toString(), UUID.fromString(mother));
        String kid2 = register(token, "Kid Two", "+919877700002", clinicClock.today(t.id()).minusYears(3).toString(), UUID.fromString(mother)); // own phone, guardian has the searched one
        register(token, "Stranger", "+919877700003", "1990-01-01", null);
        UUID doctor = UUID.randomUUID();
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO queue_entries (id, tenant_id, patient_id, doctor_id, queue_date, token_number, status, department_id, created_at, updated_at) " +
                        "VALUES (?,?,?::uuid,(SELECT id FROM staff WHERE tenant_id = ? LIMIT 1),'2026-09-01'::date,1,'completed',(SELECT id FROM departments WHERE tenant_id = ? AND is_default),now(),now())",
                doctor, t.id(), kid1, t.id(), t.id()));

        List<Map<String, Object>> rows = household(token, "9877700001");

        assertThat(rows).hasSize(3);
        Map<String, Map<String, Object>> byId = new java.util.HashMap<>();
        rows.forEach(r -> byId.put((String) r.get("patientId"), r));
        assertThat(byId.get(mother)).containsEntry("relation", "guardian").containsEntry("displayName", "Mona Said");
        assertThat(byId.get(kid1)).containsEntry("relation", "child").containsEntry("age", 6).containsEntry("guardianId", mother);
        assertThat(byId.get(kid1).get("lastVisitAt")).isNotNull();
        assertThat(byId.get(kid2)).containsEntry("relation", "child").containsEntry("age", 3); // child listed through the guardian's phone
        assertThat(byId.get(kid2).get("lastVisitAt")).isNull();
        assertThat(byId.get(mother)).containsEntry("sex", "female");
        assertThat((int) byId.get(mother).get("age")).isGreaterThanOrEqualTo(37);
    }

    @Test
    void typedFormatsMatchAndUnknownPhoneIsEmptyAndInvalidPhoneIs400() {
        SeededTenant t = seedTenant();
        String token = owner(t, "h2@a.com", "+919300000102");
        register(token, "Solo Person", "+919877700011", "1990-01-01", null);
        assertThat(household(token, "98777-00011")).hasSize(1);
        assertThat(household(token, "9877700012")).isEmpty();
        ResponseEntity<Map> bad = get(token, "/v1/patients/by-phone?phone=12");
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody().get("code")).isEqualTo("INVALID_PHONE");
    }

    @Test
    void householdIsTenantScopedAndNeedsAuth() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        String ta = owner(a, "h3a@a.com", "+919300000103");
        String tb = owner(b, "h3b@b.com", "+919300000104");
        register(ta, "Tenant A Person", "+919877700021", "1990-01-01", null);
        assertThat(household(tb, "9877700021")).isEmpty();
        assertThat(http.getForEntity(url("/v1/patients/by-phone?phone=9877700021"), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void householdReadIsLoggedOncePerLookup() {
        SeededTenant t = seedTenant();
        String token = owner(t, "h4@a.com", "+919300000105");
        String mother = register(token, "Log Mother", "+919877700031", "1988-04-02", null);
        register(token, "Log Child", "+919877700031", clinicClock.today(t.id()).minusYears(5).toString(), UUID.fromString(mother));
        household(token, "9877700031");
        long deadline = System.currentTimeMillis() + 15_000;
        int rows = 0;
        while (System.currentTimeMillis() < deadline && rows < 2) {
            rows = new JdbcTemplate(superuserDataSource()).queryForObject(
                    "SELECT count(*) FROM phi_read_log WHERE tenant_id = ? AND resource_type = 'patient_household_list'", Integer.class, t.id());
            if (rows < 2) sleep(200);
        }
        assertThat(rows).isEqualTo(2); // one row per patient returned, one lookup (the list-resource rule)
    }

    @Test
    void lookupP95Under150msOn200kPatients() {
        SeededTenant t = seedTenant();
        String token = owner(t, "h5@a.com", "+919300000106");
        JdbcTemplate su = new JdbcTemplate(superuserDataSource());
        su.update("INSERT INTO patients (tenant_id, name, phone, dob, gender) SELECT ?, 'Bulk ' || g, '+91' || (6000000000 + g)::text, date '1970-01-01' + (g % 20000), 'male' FROM generate_series(1, 200000) g", t.id());
        su.execute("ANALYZE patients");
        su.update("DELETE FROM registry_dirty WHERE tenant_id = ?", t.id()); // the bulk rows are never read via the registry; left queued they make every later drain() time out on this tenant
        String mother = register(token, "Perf Mother", "+919877700041", "1988-04-02", null);
        register(token, "Perf Child", "+919877700041", clinicClock.today(t.id()).minusYears(5).toString(), UUID.fromString(mother));
        for (int i = 0; i < 5; i++) household(token, "9877700041"); // warm up
        List<Long> ms = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            long t0 = System.nanoTime();
            assertThat(household(token, "9877700041")).hasSize(2);
            ms.add((System.nanoTime() - t0) / 1_000_000);
        }
        Collections.sort(ms);
        long p95 = ms.get((int) Math.ceil(ms.size() * 0.95) - 1);
        System.out.println("ACC-029 perf: 200k patients, 40 lookups, p50=" + ms.get(20) + "ms p95=" + p95 + "ms max=" + ms.get(39) + "ms");
        assertThat(p95).isLessThan(150);
    }

    // ---------------------------------------------------------------- ACC-030

    @Test
    void samePhoneAndSimilarNamesIsASuggestionWithMatchedFieldsAndSiblingsAreNot() {
        SeededTenant t = seedTenant();
        String token = owner(t, "d1@a.com", "+919300000111");
        String a = register(token, N1, "+919877700051", "1990-01-01", null);
        String b = register(token, N2, "+919877700051", "1991-02-02", null);
        register(token, "Sibling Zed", "+919877700051", "2000-05-05", null); // same phone, different name and dob: a family, not a duplicate

        List<Map<String, Object>> s = suggestions(token);

        assertThat(s).hasSize(1);
        assertThat(s.get(0).get("matchedFields")).isEqualTo(List.of("phone", "name"));
        assertThat(((Number) s.get(0).get("score")).doubleValue()).isBetween(0.7, 0.9);
        Map<String, Object> pa = (Map<String, Object>) s.get(0).get("a");
        Map<String, Object> pb = (Map<String, Object>) s.get(0).get("b");
        assertThat(List.of((String) pa.get("patientId"), (String) pb.get("patientId"))).containsExactlyInAnyOrder(a, b);
        assertThat(pa).containsKeys("mrn", "displayName", "dob", "phone");
        assertThat(s.get(0).get("suggestionId")).isEqualTo(pa.get("patientId") + "_" + pb.get("patientId"));
    }

    @Test
    void samePhoneAndSameDobWithDifferentNamesIsASuggestionAndDobAloneIsNot() {
        SeededTenant t = seedTenant();
        String token = owner(t, "d2@a.com", "+919300000112");
        register(token, "Karim Nasser", "+919877700061", "1985-03-03", null);
        register(token, "Omar Haddad", "+919877700061", "1985-03-03", null);
        register(token, "Lina Mansour", "+919877700062", "1985-03-03", null); // same dob only
        List<Map<String, Object>> s = suggestions(token);
        assertThat(s).hasSize(1);
        assertThat(s.get(0).get("matchedFields")).isEqualTo(List.of("phone", "dob"));
    }

    @Test
    void mergeNeedsStepUpWritesAuditAndUndoWorksAt29DaysButNotAt31() {
        SeededTenant t = seedTenant();
        String token = owner(t, "d3@a.com", "+919300000113");
        String keep = register(token, N1, "+919877700071", "1990-01-01", null);
        String dup = register(token, N2, "+919877700071", "1991-02-02", null);
        String keep2 = register(token, "Nadia Farouk Saleh", "+919877700072", "1990-01-01", null);
        String dup2 = register(token, "Nadia Farouk Saleh", "+919877700072", "1991-02-02", null);

        ResponseEntity<Map> noStepUp = exchange("/v1/patients/" + keep + "/merge", HttpMethod.POST,
                authedJsonBody(token, Map.of("duplicatePatientId", dup)), Map.class);
        assertThat(noStepUp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        mergeOk(t, token, keep, dup);
        mergeOk(t, token, keep2, dup2);
        assertThat(audit(t, "patient.merge", keep)).isEqualTo(1);
        assertThat(suggestions(token)).isEmpty(); // merged records leave the list

        ResponseEntity<Map> recent = get(token, "/v1/patients/merges");
        assertThat(recent.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) recent.getBody().get("items")).hasSize(2);
        assertThat(((Map<?, ?>) ((List<?>) recent.getBody().get("items")).get(0)).get("undoUntil")).isNotNull();

        UUID m1 = mergeId(t, dup);
        UUID m2 = mergeId(t, dup2);
        inTenantTx(t.id(), () -> jdbc.update("UPDATE patient_merges SET merged_at = now() - interval '29 days' WHERE id = ?", m1));
        inTenantTx(t.id(), () -> jdbc.update("UPDATE patient_merges SET merged_at = now() - interval '31 days' WHERE id = ?", m2));

        ResponseEntity<Void> ok = undo(t, token, m1, Void.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(audit(t, "patient.merge_undo", keep)).isEqualTo(1);
        assertThat((List<?>) get(token, "/v1/patients/merges").getBody().get("items")).isEmpty(); // undone one left the list, the 31-day one is past the window
        ResponseEntity<Map> late = undo(t, token, m2, Map.class);
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(late.getBody().get("code")).isEqualTo("UNDO_WINDOW_PASSED");
        assertThat(undo(t, token, UUID.randomUUID(), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        String status = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT status FROM patients WHERE id = ?::uuid", String.class, dup2));
        assertThat(status).isEqualTo("merged"); // the late undo changed nothing
    }

    @Test
    void dismissedSuggestionStaysGoneUntilAMatchedFieldChanges() {
        SeededTenant t = seedTenant();
        String token = owner(t, "d4@a.com", "+919300000114");
        register(token, N1, "+919877700081", "1990-01-01", null);
        String b = register(token, N2, "+919877700081", "1991-02-02", null);
        String id = (String) suggestions(token).get(0).get("suggestionId");

        ResponseEntity<Void> d = exchange("/v1/patients/duplicate-suggestions/" + id + "/dismiss", HttpMethod.POST, authed(token), Void.class);
        assertThat(d.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(suggestions(token)).isEmpty();

        // a field that was not matched changes: still dismissed
        ResponseEntity<Map> p = exchange("/v1/patients/" + b, HttpMethod.PATCH, authedJsonBody(token, Map.of("address", "New street 5")), Map.class);
        assertThat(p.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(suggestions(token)).isEmpty();

        // the dob now matches too: the matched fields changed, the pair is back
        p = exchange("/v1/patients/" + b, HttpMethod.PATCH, authedJsonBody(token, Map.of("dob", "1990-01-01")), Map.class);
        assertThat(p.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> back = suggestions(token);
        assertThat(back).hasSize(1);
        assertThat(back.get(0).get("matchedFields")).isEqualTo(List.of("phone", "name", "dob"));

        assertThat(audit(t, "patient.duplicate_dismiss", null)).isEqualTo(1);
        // dismissing a pair that is not (or no longer) a suggestion is 404
        assertThat(exchange("/v1/patients/duplicate-suggestions/" + UUID.randomUUID() + "_" + UUID.randomUUID() + "/dismiss", HttpMethod.POST, authed(token), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/patients/duplicate-suggestions/garbage/dismiss", HttpMethod.POST, authed(token), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void suggestionsNeedMergeAuthorityAndAreTenantIsolated() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        String ta = owner(a, "d5a@a.com", "+919300000115");
        String tb = owner(b, "d5b@b.com", "+919300000116");
        register(ta, N1, "+919877700091", "1990-01-01", null);
        register(ta, N2, "+919877700091", "1991-02-02", null);
        assertThat(suggestions(ta)).hasSize(1);
        assertThat(suggestions(tb)).isEmpty();

        UUID viewOnly = seedRole(a.id(), "Viewer " + UUID.randomUUID().toString().substring(0, 6), false, new ModuleGrant("patients", true, true, true, false, false, false, false));
        String tv = mfaLogin(a, seedStaff(a, viewOnly, "d5v@a.com", "+919300000117", true));
        assertThat(get(tv, "/v1/patients/duplicate-suggestions").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // RLS: a dismissal row of tenant A is invisible inside tenant B
        String id = (String) suggestions(ta).get(0).get("suggestionId");
        exchange("/v1/patients/duplicate-suggestions/" + id + "/dismiss", HttpMethod.POST, authed(ta), Void.class);
        assertThat(inTenantTx(a.id(), () -> jdbc.queryForObject("SELECT count(*) FROM patient_duplicate_dismissals", Integer.class))).isEqualTo(1);
        assertThat(inTenantTx(b.id(), () -> jdbc.queryForObject("SELECT count(*) FROM patient_duplicate_dismissals", Integer.class))).isEqualTo(0);
    }

    @Test
    void suggestionPageOn20kPatientsIsUnder500msAndPagesCoverEveryDuplicate() {
        SeededTenant t = seedTenant();
        String token = owner(t, "d6@a.com", "+919300000118");
        JdbcTemplate su = new JdbcTemplate(superuserDataSource());
        su.update("INSERT INTO patients (tenant_id, name, phone, dob, gender) SELECT ?, 'Bulk ' || g || ' Person', '+91' || (6000000000 + g)::text, date '1970-01-01' + (g % 20000), 'male' FROM generate_series(1, 20000) g", t.id());
        su.execute("ANALYZE patients");
        su.update("DELETE FROM registry_dirty WHERE tenant_id = ?", t.id()); // the bulk rows are never read via the registry; left queued they make every later drain() time out on this tenant
        register(token, N1, "+919877700099", "1990-01-01", null);
        register(token, N2, "+919877700099", "1991-02-02", null);
        long worst = 0;
        int found = 0;
        String cursor = null;
        do {
            long t0 = System.nanoTime();
            ResponseEntity<Map> r = get(token, "/v1/patients/duplicate-suggestions" + (cursor == null ? "" : "?cursor=" + cursor));
            worst = Math.max(worst, (System.nanoTime() - t0) / 1_000_000);
            found += ((List<?>) r.getBody().get("items")).size();
            cursor = (String) r.getBody().get("nextCursor");
        } while (cursor != null);
        System.out.println("ACC-030 perf: 20k patients, slowest page " + worst + "ms");
        assertThat(found).isEqualTo(1);
        assertThat(worst).isLessThan(500);
    }

    // ---------------------------------------------------------------- helpers

    private void mergeOk(SeededTenant t, String token, String keep, String dup) {
        String su = stepUp(t, token);
        ResponseEntity<Map> r = exchange("/v1/patients/" + keep + "/merge", HttpMethod.POST,
                authedJsonBodyWithHeader(token, "X-Step-Up-Token", su, Map.of("duplicatePatientId", dup)), Map.class);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
    }

    private <T> ResponseEntity<T> undo(SeededTenant t, String token, UUID mergeId, Class<T> type) {
        String su = stepUp(t, token);
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.set("X-Step-Up-Token", su);
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        return exchange("/v1/patients/merges/" + mergeId + "/undo", HttpMethod.POST, new HttpEntity<>(h), type);
    }

    private UUID mergeId(SeededTenant t, String dup) {
        return inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT id FROM patient_merges WHERE duplicate_patient_id = ?::uuid", UUID.class, dup));
    }

    private int audit(SeededTenant t, String action, String entity) {
        return new JdbcTemplate(superuserDataSource()).queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?" + (entity == null ? "" : " AND entity_id = ?::uuid"),
                Integer.class, entity == null ? new Object[]{t.id(), action} : new Object[]{t.id(), action, entity});
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
