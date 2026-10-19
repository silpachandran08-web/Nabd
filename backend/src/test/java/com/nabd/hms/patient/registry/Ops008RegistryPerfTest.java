package com.nabd.hms.patient.registry;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPS-008 AC2 measured in-process: a 20,000-patient tenant (the AC's tenant size), optionally beside a platform-wide
 * filler tenant (-Dops008.fillerPatients=480000 gives the AC's 500k total), every filter and three searches, 40 timed
 * sequential requests each through the full HTTP stack (auth, RLS, JSON). One client, not k6 concurrency: the k6
 * profile (perf/k6/registry.js, OPS-046) is the load-level proof. Writes target/ops008-registry-perf.json.
 */
class Ops008RegistryPerfTest extends ApiTestBase {

    private static final int TENANT_PATIENTS = 20_000;
    private static final int SAMPLES = 40;

    @Autowired RegistryReconciler reconciler;

    @Test
    void registryP95IsUnder300msForEveryFilterOnA20kPatientTenant() throws Exception {
        int filler = Integer.getInteger("ops008.fillerPatients", 0);
        SeededTenant t = seedTenant();
        SeededStaff staff = seedStaff(t, seedFullAccessRole(t.id()), "o8perf" + UUID.randomUUID() + "@a.com", "+91984" + (1000000 + (int) (Math.random() * 8_000_000)), false);
        String token = loginAndGetAccessToken(staff);

        long seedStart = System.nanoTime();
        inTenantTx(t.id(), () -> {
            jdbc.queryForObject("SELECT setseed(0.42)", Object.class);
            UUID keeper = UUID.randomUUID();
            jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender) VALUES (?,?,'Keeper Record','+917999999999','1960-01-01','male')", keeper, t.id());
            // 1 in 100 shares the previous patient's phone (duplicate candidates)
            jdbc.update("""
                    INSERT INTO patients (tenant_id, name, phone, dob, gender)
                    SELECT ?, initcap(chr(97 + (random() * 25)::int) || substr(md5(g::text), 1, 6) || ' ' || substr(md5((g * 7)::text), 1, 8)),
                           '+9178' || lpad((CASE WHEN g % 100 = 0 THEN g - 1 ELSE g END)::text, 8, '0'),
                           date '1940-01-01' + (random() * 30000)::int, CASE WHEN g % 2 = 0 THEN 'male' ELSE 'female' END
                    FROM generate_series(1, ?) g""", t.id(), TENANT_PATIENTS - 1);
            jdbc.update("""
                    INSERT INTO queue_entries (tenant_id, patient_id, doctor_id, department_id, queue_date, token_number, status)
                    SELECT ?, id, ?, (SELECT id FROM departments WHERE tenant_id = ? AND is_default), current_date - 1 - (random() * 300)::int,
                           row_number() OVER (ORDER BY id), 'completed' FROM patients WHERE random() < 0.4""", t.id(), staff.id(), t.id());
            jdbc.update("INSERT INTO invoices (tenant_id, patient_id, subtotal, tax, total, created_by) SELECT tenant_id, id, 400, 0, 400, ? FROM patients WHERE random() < 0.05",
                    staff.id());
            jdbc.update("""
                    INSERT INTO chronic_conditions (tenant_id, patient_id, condition, status, review_due_date, recorded_by)
                    SELECT ?, id, 'Cond', 'active', current_date + (random() * 60 - 30)::int, ? FROM patients WHERE random() < 0.1""", t.id(), staff.id());
            jdbc.update("UPDATE patients SET status = 'merged', merged_into_id = ? WHERE id <> ? AND random() < 0.01", keeper, keeper);
        });
        long seedMs = (System.nanoTime() - seedStart) / 1_000_000;
        inTenantTx(t.id(), () -> jdbc.update("DELETE FROM registry_dirty")); // the backfill below recomputes all; keeps other tests' drain() cheap

        Ops008RegistryReadModelApiTest.analyze();
        long backfillStart = System.nanoTime();
        long backfilled = reconciler.reconcileTenant(t.id()); // the real backfill path (also the nightly duplicate job)
        long backfillMs = (System.nanoTime() - backfillStart) / 1_000_000;
        assertThat(backfilled).isEqualTo(TENANT_PATIENTS);

        long fillerMs = filler > 0 ? seedFiller(filler) : 0;
        try (Connection su = superuserConnection(); Statement st = su.createStatement()) {
            for (String table : List.of("patients", "patient_registry_summary", "queue_entries", "invoices", "chronic_conditions", "appointments", "consents")) {
                st.execute("ANALYZE " + table);
            }
        }
        long platformPatients;
        try (Connection su = superuserConnection(); Statement st = su.createStatement()) {
            var rs = st.executeQuery("SELECT count(*) FROM patients");
            rs.next();
            platformPatients = rs.getLong(1);
        }

        Map<String, String> profiles = new LinkedHashMap<>();
        for (String f : List.of("all", "recent", "followup", "package", "balance", "duplicate", "archived")) {
            profiles.put("filter=" + f, "?filter=" + f + "&limit=50");
        }
        profiles.put("q=name-fragment", "?q=ab&limit=50");
        profiles.put("q=mobile-digits", "?q=0001234&limit=50");
        profiles.put("q=no-match", "?q=zzqqxx&limit=50");
        profiles.put("filter=all page 2", null); // keyset second page, cursor filled below

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("tenantPatients", TENANT_PATIENTS);
        report.put("platformPatients", platformPatients);
        report.put("seedMs", seedMs);
        report.put("backfillMs", backfillMs);
        report.put("fillerSeedMs", fillerMs);
        report.put("samplesPerProfile", SAMPLES);
        Map<String, Object> results = new LinkedHashMap<>();
        List<String> over = new ArrayList<>();
        for (var e : profiles.entrySet()) {
            String query = e.getValue();
            if (query == null) {
                Map<?, ?> first = exchange("/v1/patients/registry?limit=50", HttpMethod.GET, authed(token), Map.class).getBody();
                query = "?limit=50&cursor=" + ((Map<?, ?>) first.get("page")).get("nextCursor");
            }
            for (int i = 0; i < 5; i++) call(token, query); // warm-up
            long[] ms = new long[SAMPLES];
            for (int i = 0; i < SAMPLES; i++) {
                long s = System.nanoTime();
                call(token, query);
                ms[i] = (System.nanoTime() - s) / 1_000_000;
            }
            java.util.Arrays.sort(ms);
            long p50 = ms[SAMPLES / 2], p95 = ms[(int) Math.ceil(SAMPLES * 0.95) - 1], p99 = ms[SAMPLES - 1];
            results.put(e.getKey(), Map.of("p50Ms", p50, "p95Ms", p95, "maxMs", p99));
            System.out.printf("OPS-008 perf %-22s p50=%4d ms  p95=%4d ms  max=%4d ms%n", e.getKey(), p50, p95, p99);
            if (p95 >= 300) over.add(e.getKey() + " p95=" + p95);
        }
        report.put("results", results);
        System.out.printf("OPS-008 perf tenant=%d platform=%d seed=%d ms backfill=%d ms filler=%d ms%n", TENANT_PATIENTS, platformPatients, seedMs, backfillMs, fillerMs);
        Files.writeString(Path.of("target/ops008-registry-perf.json"), objectMapper.writeValueAsString(report));
        assertThat(over).as("profiles with p95 >= 300 ms").isEmpty();
    }

    private void call(String token, String query) {
        ResponseEntity<Map> r = exchange("/v1/patients/registry" + query, HttpMethod.GET, authed(token), Map.class);
        assertThat(r.getStatusCode().value()).as(query).isEqualTo(200);
    }

    /** Platform-wide volume in one other tenant, written as superuser with triggers off so nothing is queued for the projector. */
    private long seedFiller(int n) throws Exception {
        long start = System.nanoTime();
        SeededTenant f = seedTenant();
        try (Connection su = superuserConnection(); Statement st = su.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute("INSERT INTO patients (tenant_id, name, phone, dob, gender) SELECT '" + f.id() + "', 'Filler ' || md5(g::text), '+9170' || lpad(g::text, 8, '0'), "
                    + "date '1950-01-01' + (g % 20000), 'female' FROM generate_series(1, " + n + ") g");
            st.execute("INSERT INTO patient_registry_summary (tenant_id, patient_id, name_search, mrn, phone_last10, change_seq) "
                    + "SELECT tenant_id, id, lower(name), mrn, right(regexp_replace(phone, '\\D', '', 'g'), 10), nextval('patient_registry_change_seq') "
                    + "FROM patients WHERE tenant_id = '" + f.id() + "'");
        }
        return (System.nanoTime() - start) / 1_000_000;
    }
}
