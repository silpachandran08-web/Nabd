package com.nabd.hms.patient.registry;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** OPS-008: GET /v1/patients/registry reads patient_registry_summary with SQL filters and keyset paging. */
class Ops008RegistryReadModelApiTest extends ApiTestBase {

    @Autowired RegistryReconciler reconciler;

    private record Ctx(SeededTenant t, SeededStaff staff, String token) { }

    private Ctx ctx() {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "o8" + UUID.randomUUID() + "@a.com", "+91983" + (1000000 + (int) (Math.random() * 8_000_000)), false);
        return new Ctx(t, s, loginAndGetAccessToken(s));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(Ctx c, String query) {
        ResponseEntity<Map> r = exchange("/v1/patients/registry" + query, HttpMethod.GET, authed(c.token), Map.class);
        assertThat(r.getStatusCode()).as(query + " " + r.getBody()).isEqualTo(HttpStatus.OK);
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

    /** Walks every page of a filter; fails on a repeated row. */
    private List<Map<String, Object>> walk(Ctx c, String filter, int limit) {
        List<Map<String, Object>> all = new ArrayList<>();
        Set<Object> ids = new java.util.HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            Map<String, Object> page = get(c, "?filter=" + filter + "&limit=" + limit + (cursor == null ? "" : "&cursor=" + cursor));
            for (Map<String, Object> r : rows(page)) {
                assertThat(ids.add(r.get("id"))).as("row repeated across pages: " + r.get("id")).isTrue();
                all.add(r);
            }
            cursor = next(page);
        } while (cursor != null && ++pages < 1000);
        return all;
    }

    /** Bulk-loaded in one transaction: refresh planner stats the way autovacuum would before the backfill runs. */
    static void analyze() {
        try (java.sql.Connection su = superuserConnection(); java.sql.Statement st = su.createStatement()) {
            for (String t : List.of("patients", "patient_registry_summary", "queue_entries", "invoices", "chronic_conditions", "appointments")) st.execute("ANALYZE " + t);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID insertPatient(Ctx c, String name, String phone, String dob) {
        UUID id = UUID.randomUUID();
        inTenantTx(c.t.id(), () -> jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender) VALUES (?,?,?,?,?::date,'female')",
                id, c.t.id(), name, phone, dob));
        return id;
    }

    // AC1: 4,000 patients, 'balance' with no query, names A-Z all appear (no 3,000 cap)
    @Test
    void ac1_balanceFilterWithoutQueryCoversEveryLetterAcross4000Patients() {
        Ctx c = ctx();
        inTenantTx(c.t.id(), () -> {
            jdbc.update("""
                    INSERT INTO patients (tenant_id, name, phone, dob, gender)
                    SELECT ?, chr(65 + g % 26) || 'pat ' || md5(g::text), '+9177' || lpad(g::text, 8, '0'), date '1950-01-01' + (g % 20000), 'female'
                    FROM generate_series(1, 4000) g""", c.t.id());
            jdbc.update("INSERT INTO invoices (tenant_id, patient_id, subtotal, tax, total, created_by) SELECT tenant_id, id, 100, 0, 100, ? FROM patients",
                    c.staff.id());
        });
        inTenantTx(c.t.id(), () -> jdbc.update("DELETE FROM registry_dirty")); // the backfill below recomputes all; keeps other tests' drain() cheap
        analyze();
        reconciler.reconcileTenant(c.t.id());

        Map<String, Object> first = get(c, "?filter=balance");
        assertThat(((Map<?, ?>) first.get("counts")).get("balance")).isEqualTo(4000);
        assertThat(first).containsEntry("matchedTotal", 4000).containsEntry("hasMore", true);
        assertThat(rows(first)).hasSize(200);

        List<Map<String, Object>> all = walk(c, "balance", 200);
        assertThat(all).hasSize(4000);
        Set<Character> letters = new TreeSet<>();
        all.forEach(r -> letters.add(((String) r.get("name")).charAt(0)));
        assertThat(letters).hasSize(26).contains('A', 'M', 'N', 'Z');
        assertThat(all).allSatisfy(r -> assertThat(r).containsEntry("balanceDue", true));
        List<String> names = all.stream().map(r -> ((String) r.get("name")).toLowerCase()).toList();
        assertThat(names).isSorted();
    }

    // AC3: page 2 after a new registration repeats and skips nothing
    @Test
    void ac3_keysetCursorNeitherRepeatsNorSkipsWhenPatientsAreRegisteredBetweenPages() {
        Ctx c = ctx();
        List<UUID> original = new ArrayList<>();
        for (String n : List.of("Kay Adams", "Kay Baker", "Kay Clark", "Kay Davis", "Kay Evans", "Kay Fox")) {
            original.add(insertPatient(c, n, "+9176000" + (10000 + original.size()), "1980-01-0" + (1 + original.size())));
        }
        reconciler.reconcileTenant(c.t.id());

        Map<String, Object> p1 = get(c, "?limit=3");
        assertThat(rows(p1)).extracting(r -> r.get("name")).containsExactly("Kay Adams", "Kay Baker", "Kay Clark");
        // registered between pages: one sorts before the cursor (offset paging would repeat Kay Clark), one after
        insertPatient(c, "Kay Aaron", "+917600020001", "1990-02-02");
        insertPatient(c, "Kay Zed", "+917600020002", "1990-03-03");
        reconciler.reconcileTenant(c.t.id());

        List<Object> seen = new ArrayList<>(rows(p1).stream().map(r -> r.get("id")).toList());
        String cursor = next(p1);
        while (cursor != null) {
            Map<String, Object> p = get(c, "?limit=3&cursor=" + cursor);
            rows(p).forEach(r -> seen.add(r.get("id")));
            cursor = next(p);
        }
        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).containsAll(original.stream().map(UUID::toString).toList());
        assertThat(seen).hasSize(7); // the 6 originals plus Kay Zed (after the cursor); Kay Aaron belongs to page 1's range

        // the cursor is opaque and carries no patient name (PHI never in a URL)
        String raw = new String(Base64.getUrlDecoder().decode(next(p1)), StandardCharsets.ISO_8859_1);
        assertThat(raw.toLowerCase()).doesNotContain("kay");
    }

    // AC4: the nightly duplicate job flags both patients sharing phone_last10 and a similar normalised name
    @Test
    void ac4_nightlyDuplicateJobFlagsSamePhoneLast10WithSimilarNameAndListsThemUnderDuplicate() {
        Ctx c = ctx();
        UUID first = insertPatient(c, "Ravi Shankar", "+919845012345", "1971-01-01");
        reconciler.reconcileTenant(c.t.id());
        // a later record of the same person: other number format (trunk 0), name typed differently, other DOB
        UUID second = insertPatient(c, "ravi  shanker", "09845012345", "1972-02-02");
        UUID family = insertPatient(c, "Meena Iyer", "00919845012345", "1990-03-03"); // same last 10 digits, unrelated name
        UUID solo = insertPatient(c, "Solo Person", "+919845099999", "1973-03-03");

        reconciler.reconcileTenant(c.t.id()); // the nightly pass (RegistryReconciler.runAll, cron 02:30) for this tenant

        Map<String, Object> dup = get(c, "?filter=duplicate");
        assertThat(rows(dup)).extracting(r -> r.get("id")).containsExactlyInAnyOrder(first.toString(), second.toString());
        assertThat(rows(dup)).allSatisfy(r -> assertThat(r).containsEntry("duplicate", true));
        assertThat(((Map<?, ?>) dup.get("counts")).get("duplicate")).isEqualTo(2);
        Map<String, Map<String, Object>> byId = new java.util.HashMap<>();
        rows(get(c, "")).forEach(r -> byId.put((String) r.get("id"), r));
        assertThat(byId.get(family.toString())).containsEntry("duplicate", false);
        assertThat(byId.get(solo.toString())).containsEntry("duplicate", false);
    }

    // AC5: limit=0 or 500 is 400 Problem+JSON INVALID_LIMIT; 1 and 200 are fine; a garbled cursor is 400 invalid-cursor
    @Test
    void ac5_limitOutside1To200IsInvalidLimit() {
        Ctx c = ctx();
        for (String bad : List.of("0", "500", "201", "-1")) {
            ResponseEntity<Map> r = exchange("/v1/patients/registry?limit=" + bad, HttpMethod.GET, authed(c.token), Map.class);
            assertThat(r.getStatusCode()).as(bad).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(r.getHeaders().getContentType().toString()).contains("application/problem+json");
            assertThat(r.getBody()).containsEntry("code", "INVALID_LIMIT");
            assertThat((String) r.getBody().get("type")).endsWith("/validation");
        }
        assertThat(get(c, "?limit=1")).containsEntry("hasMore", false);
        assertThat(((Map<?, ?>) get(c, "?limit=200").get("page")).get("limit")).isEqualTo(200);
        ResponseEntity<Map> badCursor = exchange("/v1/patients/registry?cursor=" + Base64.getUrlEncoder().encodeToString("rk1|x".getBytes(StandardCharsets.UTF_8)),
                HttpMethod.GET, authed(c.token), Map.class);
        assertThat(badCursor.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) badCursor.getBody().get("type")).endsWith("/invalid-cursor");
    }

    // filters and search run in SQL: each filter, q by name / MRN / digits, tenant isolation, no-match
    @Test
    @SuppressWarnings("unchecked")
    void filtersAndSearchAreServedFromTheSummaryAndStayInTenant() {
        Ctx c = ctx(), other = ctx();
        UUID owes = insertPatient(c, "Nadia Owes", "+917500000001", "1980-01-01");
        UUID archived = insertPatient(c, "Arch Ived", "+917500000002", "1980-01-02");
        UUID plain = insertPatient(c, "Plain 100% Jane_Doe", "+917500000003", "1980-01-03");
        insertPatient(other, "Nadia Elsewhere", "+917500000009", "1980-01-09");
        inTenantTx(c.t.id(), () -> {
            jdbc.update("INSERT INTO invoices (tenant_id, patient_id, subtotal, tax, total, created_by) VALUES (?,?,300,0,300,?)", c.t.id(), owes, c.staff.id());
            jdbc.update("UPDATE patients SET status = 'merged', merged_into_id = ? WHERE id = ?", owes, archived);
        });
        reconciler.reconcileTenant(c.t.id());
        reconciler.reconcileTenant(other.t.id());

        Map<String, Object> all = get(c, "");
        assertThat((Map<String, Object>) all.get("counts")).containsEntry("all", 2).containsEntry("archived", 1).containsEntry("balance", 1);
        assertThat(rows(get(c, "?filter=archived"))).extracting(r -> r.get("id")).containsExactly(archived.toString());
        assertThat(rows(get(c, "?filter=balance"))).extracting(r -> r.get("id")).containsExactly(owes.toString());
        assertThat(rows(get(c, "?q=nadia"))).extracting(r -> r.get("id")).containsExactly(owes.toString()); // never the other tenant's Nadia
        assertThat(rows(get(c, "?q=00000003"))).extracting(r -> r.get("id")).containsExactly(plain.toString());
        assertThat(rows(get(c, "?q=+91 7500000003"))).extracting(r -> r.get("id")).containsExactly(plain.toString()); // typed with country code
        assertThat(rows(get(c, "?q=100%"))).extracting(r -> r.get("id")).containsExactly(plain.toString()); // % is literal, not a wildcard
        assertThat(get(c, "?q=_")).containsEntry("matchedTotal", 1);
        String mrn = inTenantTx(c.t.id(), () -> jdbc.queryForObject("SELECT mrn FROM patients WHERE id = ?", String.class, owes));
        assertThat(rows(get(c, "?q=" + mrn.toLowerCase()))).extracting(r -> r.get("id")).contains(owes.toString());
        assertThat(get(c, "?q=zzzz-nobody")).containsEntry("matchedTotal", 0).containsEntry("hasMore", false);
        assertThat(rows(get(other, ""))).extracting(r -> r.get("name")).containsExactly("Nadia Elsewhere");
    }
}
