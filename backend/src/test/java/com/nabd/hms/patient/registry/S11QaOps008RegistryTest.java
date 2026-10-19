package com.nabd.hms.patient.registry;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA (S11) for OPS-008, written from the story's acceptance criteria before reading the engineer's report. */
class S11QaOps008RegistryTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (Math.random() * 1_000_000));

    @Autowired RegistryReconciler reconciler;

    private record Ctx(SeededTenant t, SeededStaff staff, String token) { }

    private Ctx ctx() {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "q4o8" + UUID.randomUUID() + "@a.com", phone("+91984"), false);
        return new Ctx(t, s, loginAndGetAccessToken(s));
    }

    private static String phone(String prefix) {
        return prefix + String.format("%07d", SEQ.incrementAndGet() % 10_000_000);
    }

    private ResponseEntity<Map> raw(String token, String query) {
        return exchange("/v1/patients/registry" + query, HttpMethod.GET, authed(token), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(Ctx c, String query) {
        ResponseEntity<Map> r = raw(c.token, query);
        assertThat(r.getStatusCode()).as(query + " -> " + r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("rows");
    }

    @SuppressWarnings("unchecked")
    private static String next(Map<String, Object> page) {
        return (String) ((Map<String, Object>) page.get("page")).get("nextCursor");
    }

    private List<String> walkIds(Ctx c, String query, int limit) {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        do {
            Map<String, Object> p = get(c, "?limit=" + limit + query + (cursor == null ? "" : "&cursor=" + cursor));
            rows(p).forEach(r -> ids.add((String) r.get("id")));
            cursor = next(p);
        } while (cursor != null && ++guard < 500);
        return ids;
    }

    private UUID insertPatient(Ctx c, String name) {
        UUID id = UUID.randomUUID();
        inTenantTx(c.t.id(), () -> jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender) VALUES (?,?,?,?,date '1985-05-05','male')",
                id, c.t.id(), name, phone("+91760")));
        return id;
    }

    // AC1 (cap regression): 3,050 "A" names sort first, so a 3,000 cap ordered by name would cut every "Z" patient.
    // Only the Z patients owe money; filter=balance with no q must return all 30 of them.
    @Test
    void ac1_balanceFilterFindsPatientsSortedAfterTheOld3000Cap() {
        Ctx c = ctx();
        inTenantTx(c.t.id(), () -> {
            jdbc.update("""
                    INSERT INTO patients (tenant_id, name, phone, dob, gender)
                    SELECT ?, 'Aaqa ' || lpad(g::text, 5, '0'), '+9175' || lpad(g::text, 8, '0'), date '1960-01-01' + g, 'female'
                    FROM generate_series(1, 3050) g""", c.t.id());
            jdbc.update("""
                    INSERT INTO patients (tenant_id, name, phone, dob, gender)
                    SELECT ?, 'Zuqa ' || lpad(g::text, 3, '0'), '+9174' || lpad(g::text, 8, '0'), date '1970-01-01' + g, 'male'
                    FROM generate_series(1, 30) g""", c.t.id());
            jdbc.update("INSERT INTO invoices (tenant_id, patient_id, subtotal, tax, total, created_by) "
                    + "SELECT tenant_id, id, 50, 0, 50, ? FROM patients WHERE name LIKE 'Zuqa %'", c.staff.id());
            jdbc.update("DELETE FROM registry_dirty");
        });
        Ops008RegistryReadModelApiTest.analyze();
        reconciler.reconcileTenant(c.t.id());

        Map<String, Object> bal = get(c, "?filter=balance&limit=200");
        assertThat(((Map<?, ?>) bal.get("counts")).get("balance")).isEqualTo(30);
        assertThat(rows(bal)).hasSize(30).allSatisfy(r -> assertThat((String) r.get("name")).startsWith("Zuqa "));
        assertThat(next(bal)).isNull();

        List<String> all = walkIds(c, "&filter=all", 200);
        assertThat(all).hasSize(3080).doesNotHaveDuplicates();
        assertThat(((Map<?, ?>) get(c, "?limit=1").get("counts")).get("all")).isEqualTo(3080);
    }

    // AC3: keyset ties. Several patients share the exact same name, so (name_search, patient_id) must break the tie;
    // a page boundary falling inside the tie group and a same-named registration between pages repeat or skip nothing.
    @Test
    void ac3_keysetIsStableInsideATieOfIdenticalNamesWithAnInsertBetweenPages() {
        Ctx c = ctx();
        Set<String> originals = new HashSet<>();
        for (int i = 0; i < 5; i++) originals.add(insertPatient(c, "Sam Same").toString());
        reconciler.reconcileTenant(c.t.id());

        Map<String, Object> p1 = get(c, "?limit=2");
        List<String> seen = new ArrayList<>();
        rows(p1).forEach(r -> seen.add((String) r.get("id")));
        insertPatient(c, "Sam Same"); // lands before or after the cursor depending on its random id
        insertPatient(c, "Aaron First"); // sorts before the cursor: must not shift page 2
        reconciler.reconcileTenant(c.t.id());

        String cursor = next(p1);
        while (cursor != null) {
            Map<String, Object> p = get(c, "?limit=2&cursor=" + cursor);
            rows(p).forEach(r -> seen.add((String) r.get("id")));
            cursor = next(p);
        }
        assertThat(seen).doesNotHaveDuplicates().containsAll(originals);
        assertThat(seen.size()).isBetween(5, 6);
    }

    // AC3 negative: a cursor is bound to the tenant that issued it and to this endpoint
    @Test
    void ac3_cursorFromAnotherTenantOrGarbageIsInvalidCursor() {
        Ctx a = ctx(), b = ctx();
        for (int i = 0; i < 3; i++) insertPatient(a, "Cur Sor " + i);
        reconciler.reconcileTenant(a.t.id());
        String cursor = next(get(a, "?limit=1"));
        assertThat(cursor).isNotNull();

        for (String bad : List.of(cursor, "not-a-cursor", "AAAA")) {
            ResponseEntity<Map> r = raw(b.token, "?limit=1&cursor=" + bad);
            assertThat(r.getStatusCode()).as(bad).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(r.getHeaders().getContentType().toString()).contains("application/problem+json");
            assertThat((String) r.getBody().get("type")).endsWith("invalid-cursor");
        }
    }

    // AC4: nightly duplicate job, other phone format + name with different case/spacing/punctuation
    @Test
    void ac4_duplicateJobFlagsBothRecordsOfOnePersonAndOnlyThem() {
        Ctx c = ctx();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), other = UUID.randomUUID();
        inTenantTx(c.t.id(), () -> {
            jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender) VALUES (?,?,?,?,date '1979-04-04','female')",
                    a, c.t.id(), "Fatima Rahman", "+919812340077");
            jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender) VALUES (?,?,?,?,date '1979-04-05','female')",
                    b, c.t.id(), "FATIMA  rahman", "098123 40077");
            jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender) VALUES (?,?,?,?,date '1979-04-06','female')",
                    other, c.t.id(), "Fatima Rahman", "+919812349999"); // same name, other number: not a phone duplicate
        });
        reconciler.reconcileTenant(c.t.id());

        Map<String, Object> dup = get(c, "?filter=duplicate");
        assertThat(rows(dup)).extracting(r -> r.get("id")).containsExactlyInAnyOrder(a.toString(), b.toString());
        assertThat(((Map<?, ?>) dup.get("counts")).get("duplicate")).isEqualTo(2);
    }

    // AC5: limits (boundaries 1 and 200 accepted; 0, 201, 500 and non-numeric refused as Problem+JSON)
    @Test
    void ac5_limitBoundariesAndInvalidLimitProblem() {
        Ctx c = ctx();
        for (String bad : List.of("0", "201", "500")) {
            ResponseEntity<Map> r = raw(c.token, "?limit=" + bad);
            assertThat(r.getStatusCode()).as(bad).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(r.getHeaders().getContentType().toString()).contains("application/problem+json");
            assertThat(r.getBody()).containsEntry("code", "INVALID_LIMIT");
        }
        ResponseEntity<Map> nonNumeric = raw(c.token, "?limit=ten");
        assertThat(nonNumeric.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(nonNumeric.getHeaders().getContentType().toString()).contains("application/problem+json");
        assertThat(raw(c.token, "?limit=1").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(raw(c.token, "?limit=200").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(raw(c.token, "?filter=everyone").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // auth: patients:view required; tenant isolation of every filter
    @Test
    void registryNeedsPatientsViewAndNeverShowsAnotherTenant() {
        Ctx a = ctx(), b = ctx();
        insertPatient(a, "Only Alpha");
        insertPatient(b, "Only Bravo");
        reconciler.reconcileTenant(a.t.id());
        reconciler.reconcileTenant(b.t.id());
        for (String f : List.of("all", "recent", "followup", "package", "balance", "duplicate", "archived")) {
            assertThat(rows(get(a, "?filter=" + f))).extracting(r -> r.get("name")).doesNotContain("Only Bravo");
        }
        assertThat(rows(get(b, ""))).extracting(r -> r.get("name")).containsExactly("Only Bravo");

        UUID queueOnly = seedRole(a.t.id(), "Desk", false, new ModuleGrant("queue", true, true, false, false, false, false, false));
        SeededStaff desk = seedStaff(a.t, queueOnly, "q4desk" + UUID.randomUUID() + "@a.com", phone("+91985"), false);
        ResponseEntity<Map> denied = raw(loginAndGetAccessToken(desk), "");
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getHeaders().getContentType().toString()).contains("application/problem+json");
    }
}
