package com.nabd.hms.patient;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** OPS-009: golden normalisers (Java == SQL), search behaviour per acceptance criterion, per-branch EXPLAIN, p95 at 60k rows. */
class Ops009PatientSearchApiTest extends ApiTestBase {

    private static final List<UUID> TENANTS = new ArrayList<>();

    @Autowired PatientRepository repo;

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection c = superuserConnection(); Statement s = c.createStatement()) {
            for (UUID t : TENANTS) s.execute("DELETE FROM patients WHERE tenant_id = '" + t + "'");
        }
    }

    private void insert(UUID t, String name, String phone, String mrn) {
        inTenantTx(t, () -> jdbc.update("INSERT INTO patients (tenant_id, name, phone, dob, gender, mrn) VALUES (?,?,?,'1990-01-01','male',?)",
                t, name, phone, mrn));
    }

    private String token(SeededTenant t) {
        TENANTS.add(t.id());
        return loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "s" + UUID.randomUUID() + "@a.com", "+91984" + (1000000 + (int) (Math.random() * 8_000_000)), false));
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> search(String token, String q) {
        return http.exchange(java.net.URI.create(url("/v1/patients?q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"))), HttpMethod.GET, authed(token), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(ResponseEntity<Map> r) {
        return (List<Map<String, Object>>) r.getBody().get("data");
    }

    @Test
    void goldenFileMatchesJavaAndSqlNormalisers() throws Exception {
        List<String> lines = Files.readAllLines(Path.of("src/test/resources/ops009-search-golden.tsv"));
        assertThat(lines).hasSizeGreaterThan(15);
        try (Connection c = superuserConnection()) {
            for (String line : lines) {
                String[] f = line.split("\t", -1);
                boolean name = f[0].equals("name");
                String javaOut = name ? SearchKeys.normaliseName(f[1]) : SearchKeys.last10(f[1]);
                try (PreparedStatement ps = c.prepareStatement(name ? "SELECT normalise_name(?)" : "SELECT right(nsn_digits(?), 10)")) {
                    ps.setString(1, f[1]);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        assertThat(rs.getString(1)).as("sql " + line).isEqualTo(f[2]);
                    }
                }
                assertThat(javaOut).as("java " + line).isEqualTo(f[2]);
            }
        }
    }

    @Test
    void phonePrefixFindsIndianNumberAndRegionNumbersUseTheNationalNumber() throws Exception {
        SeededTenant t = seedTenant();
        String tok = token(t);
        insert(t.id(), "Ravi Kumar", "+91 98123 45678", "MRN-900001");
        insert(t.id(), "Uae Person", "+971501234567", "MRN-900002");
        insert(t.id(), "Qatar Person", "+97455123456", "MRN-900003");

        ResponseEntity<Map> r = search(tok, "98123");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(items(r)).singleElement().satisfies(i -> {
            assertThat(i.get("name")).isEqualTo("Ravi Kumar");
            assertThat(i.get("matchedOn")).isEqualTo("phone");
        });
        assertThat(items(search(tok, "050 123"))).extracting(i -> i.get("name")).containsExactly("Uae Person"); // local form, trunk 0
        assertThat(items(search(tok, "+971 50 1234"))).extracting(i -> i.get("name")).containsExactly("Uae Person");
        assertThat(items(search(tok, "55123"))).extracting(i -> i.get("name")).containsExactly("Qatar Person");
        try (Connection c = superuserConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT name, phone_last10 FROM patients WHERE tenant_id = '" + t.id() + "' ORDER BY mrn")) {
            rs.next(); assertThat(rs.getString(2)).isEqualTo("9812345678");
            rs.next(); assertThat(rs.getString(2)).isEqualTo("501234567");   // 9 digits, no "971"
            rs.next(); assertThat(rs.getString(2)).isEqualTo("55123456");    // 8 digits, no "974"
        }
    }

    @Test
    void arabicNameFoldsDiacriticsAndLettersAndOtherScriptsWork() {
        SeededTenant t = seedTenant();
        String tok = token(t);
        insert(t.id(), "مُحَمَّد الأحمد", "+919000000001", "MRN-900011");
        insert(t.id(), "فاطمة", "+919000000002", "MRN-900012");
        insert(t.id(), "राहुल शर्मा", "+919000000003", "MRN-900013");
        insert(t.id(), "Mohammed Al-Harbi", "+919000000004", "MRN-900014");

        assertThat(items(search(tok, "محمد"))).extracting(i -> i.get("mrn")).containsExactly("MRN-900011");
        assertThat(items(search(tok, "مُحَمَّد"))).hasSize(1);   // diacritics on the query side fold too
        assertThat(items(search(tok, "الاحمد"))).hasSize(1);     // alef-hamza folded
        assertThat(items(search(tok, "فاطمه"))).extracting(i -> i.get("mrn")).containsExactly("MRN-900012"); // taa marbuta -> haa
        assertThat(items(search(tok, "राहुल"))).hasSize(1);
        assertThat(items(search(tok, "al-HARBI"))).singleElement().satisfies(i -> assertThat(i.get("matchedOn")).isEqualTo("name"));
    }

    @Test
    void shortQueriesAndLimitAndMrnAndTenantIsolation() {
        SeededTenant t = seedTenant();
        SeededTenant other = seedTenant();
        String tok = token(t);
        String otherTok = token(other);
        insert(t.id(), "Short Case", "+919000000021", "Q");           // a one-character MRN is still found exactly
        insert(t.id(), "Mrn Case", "+919000000022", "MRN-900021");
        insert(other.id(), "Short Case", "+919000000021", "MRN-900099");

        assertThat(items(search(tok, "Q"))).singleElement().satisfies(i -> assertThat(i.get("matchedOn")).isEqualTo("mrn"));
        ResponseEntity<Map> tooShort = search(tok, "z");
        assertThat(tooShort.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooShort.getBody().get("code")).isEqualTo("QUERY_TOO_SHORT");
        assertThat(search(tok, "sh").getBody().get("code")).isEqualTo("QUERY_TOO_SHORT"); // below the 3-char trigram floor
        assertThat(items(search(tok, "MRN-9000"))).extracting(i -> i.get("mrn")).containsExactly("MRN-900021");
        assertThat(items(search(tok, "mrn-900021"))).hasSize(1);  // case-insensitive
        assertThat(items(search(tok, "Short Case"))).hasSize(1);  // only own tenant (RLS + tenant predicate)
        assertThat(items(search(otherTok, "9000000021"))).extracting(i -> i.get("mrn")).containsExactly("MRN-900099");

        ResponseEntity<Map> bad = exchange("/v1/patients?q=abcdef&limit=51", HttpMethod.GET, authed(tok), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody().get("code")).isEqualTo("INVALID_LIMIT");
        assertThat(exchange("/v1/patients?q=abcdef&limit=50", HttpMethod.GET, authed(tok), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(items(search(tok, "abc%"))).isEmpty(); // LIKE wildcards are literal
    }

    /** AC3 at test scale: 60k rows in the tenant beside 40k in another; every branch uses its index, then p95 through the full stack. */
    @Test
    void everyBranchUsesItsIndexAndP95IsUnder300ms() throws Exception {
        SeededTenant t = seedTenant();
        SeededTenant filler = seedTenant();
        String tok = token(t);
        TENANTS.add(filler.id());
        try (Connection c = superuserConnection(); Statement s = c.createStatement()) {
            for (UUID[] spec : new UUID[][]{{t.id()}, {filler.id()}}) {
                int n = spec[0].equals(t.id()) ? 60_000 : 40_000;
                s.execute("INSERT INTO patients (tenant_id, name, phone, dob, gender, mrn) SELECT '" + spec[0] + "', "
                        + "initcap(substr(md5(g::text),1,7)) || ' ' || initcap(substr(md5((g*7)::text),1,8)), "
                        + "'+9178' || lpad(g::text, 8, '0'), date '1960-01-01' + (g % 20000), 'male', 'MRN-P' || lpad(g::text, 7, '0') FROM generate_series(1," + n + ") g");
            }
            s.execute("ANALYZE patients");
        }
        String name = null;
        try (Connection c = superuserConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT name FROM patients WHERE tenant_id = '" + t.id() + "' AND mrn = 'MRN-P0004242'")) {
            rs.next(); name = rs.getString(1);
        }
        String nameKey = SearchKeys.normaliseName(name).substring(0, 7);
        Object[][] cases = {
                {"phone", "idx_patients_phone_last10", repo.searchSql(t.id(), "78000042", null, false, null, null, 50)},
                {"mrn-prefix", "idx_patients_mrn_prefix", repo.searchSql(t.id(), null, "MRN-P00042", true, null, null, 50)},
                {"mrn-exact", null, repo.searchSql(t.id(), null, "MRN-P0004242", false, null, null, 50)},
                {"name", "idx_patients_name_search_trgm", repo.searchSql(t.id(), null, null, false, nameKey, null, 50)},
                {"scoped-doctor", "idx_patients_phone_last10", repo.searchSql(t.id(), "78000042", null, false, null, UUID.randomUUID(), 50)},
        };
        try (Connection c = superuserConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL jit = off");
            }
            for (Object[] cs : cases) {
                PatientRepository.SearchSql q = (PatientRepository.SearchSql) cs[2];
                StringBuilder plan = new StringBuilder();
                try (PreparedStatement ps = c.prepareStatement("EXPLAIN " + q.sql())) {
                    for (int i = 0; i < q.args().size(); i++) ps.setObject(i + 1, q.args().get(i));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) plan.append(rs.getString(1)).append('\n');
                    }
                }
                assertThat(plan.toString()).as(cs[0] + " plan:\n" + plan).containsPattern("Index (Only )?Scan|Bitmap Index Scan")
                        .doesNotContain("Seq Scan on patients");
                if (cs[1] != null) assertThat(plan.toString()).as(cs[0] + " plan:\n" + plan).contains((String) cs[1]);
            }
            c.rollback();
        }
        String[] queries = {"78000042", "MRN-P00042", "MRN-P0004242", nameKey};
        List<Long> ms = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            long t0 = System.nanoTime();
            ResponseEntity<Map> r = search(tok, queries[i % queries.length]);
            ms.add((System.nanoTime() - t0) / 1_000_000);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(items(r)).isNotEmpty();
        }
        ms.sort(Long::compare);
        long p95 = ms.get((int) Math.ceil(ms.size() * 0.95) - 1);
        System.out.println("OPS-009 search p95 at 100k rows = " + p95 + " ms");
        assertThat(p95).isLessThan(300);
    }
}
