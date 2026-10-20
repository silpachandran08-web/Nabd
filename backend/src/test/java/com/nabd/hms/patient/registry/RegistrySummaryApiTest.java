package com.nabd.hms.patient.registry;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.boot.test.context.TestConfiguration;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** OPS-007: summary table parity with a reference query, listener-path freshness, reconciliation, RLS. */
class RegistrySummaryApiTest extends ApiTestBase {

    static final List<RegistryProjector.SummaryUpdated> SEEN = new CopyOnWriteArrayList<>();

    public static class Listener {
        @EventListener
        public void on(RegistryProjector.SummaryUpdated e) {
            SEEN.add(e);
        }
    }

    @TestConfiguration
    static class Capture {
        @Bean
        Listener seenListener() {
            return new Listener();
        }
    }

    @Autowired RegistryProjector projector;
    @Autowired RegistryReconciler reconciler;

    /** The reference: plain correlated subqueries per field, written independently of the projector's SQL. */
    private static final String REFERENCE = """
            SELECT p.id,
              lower(p.name) || '|' || p.mrn || '|' || right(regexp_replace(p.phone, '\\D', '', 'g'), 10)
              || '|' || COALESCE((SELECT max(q.queue_date)::text FROM queue_entries q WHERE q.patient_id = p.id AND q.status <> 'no_show'), '-')
              || '|' || COALESCE((SELECT a.start_time::text || a.doctor_id FROM appointments a WHERE a.patient_id = p.id AND a.status = 'scheduled'
                                    AND a.start_time >= now() ORDER BY a.start_time, a.id LIMIT 1), '-')
              || '|' || COALESCE((SELECT sum(i.total - i.paid) FROM invoices i WHERE i.patient_id = p.id AND i.status IN ('unpaid','partial')), 0)::numeric(14,3)
              || '|' || EXISTS (SELECT 1 FROM chronic_conditions c WHERE c.patient_id = p.id AND c.status = 'active')
              || '|' || COALESCE((SELECT min(c.review_due_date)::text FROM chronic_conditions c WHERE c.patient_id = p.id AND c.status = 'active'), '-')
              || '|' || COALESCE((SELECT x.substance FROM patient_allergies x WHERE x.patient_id = p.id AND x.active
                                    ORDER BY CASE x.severity WHEN 'severe' THEN 0 WHEN 'moderate' THEN 1 ELSE 2 END, x.recorded_at LIMIT 1), '-')
              || '|' || (p.status = 'active' AND EXISTS (SELECT 1 FROM patients d WHERE d.id <> p.id AND d.status = 'active'
                   AND ((regexp_replace(d.phone, '\\D', '', 'g') = regexp_replace(p.phone, '\\D', '', 'g')
                         AND NOT (d.guardian_id IS NOT DISTINCT FROM p.id OR p.guardian_id IS NOT DISTINCT FROM d.id
                                  OR (d.guardian_id IS NOT NULL AND d.guardian_id = p.guardian_id)))
                        OR (d.dob = p.dob AND similarity(d.name, p.name) > 0.3))))
              || '|' || (p.status <> 'active') AS v
            FROM patients p""";

    private static final String SUMMARY = """
            SELECT patient_id AS id, name_search || '|' || mrn || '|' || phone_last10
              || '|' || COALESCE(last_visit_date::text, '-')
              || '|' || COALESCE(next_appt_at::text || next_doctor_id, '-')
              || '|' || balance_due || '|' || has_active_condition || '|' || COALESCE(followup_due::text, '-')
              || '|' || COALESCE(top_allergy, '-') || '|' || duplicate_flag || '|' || archived AS v
            FROM patient_registry_summary""";

    private Map<UUID, String> toMap(String sql) {
        Map<UUID, String> m = new java.util.HashMap<>();
        jdbc.execute("SET LOCAL jit = off");
        jdbc.execute("SET LOCAL statement_timeout = '120s'"); // the O(N^2) reference is far slower than the 5 s app-role cap
        jdbc.query(sql, rs -> { m.put(rs.getObject("id", UUID.class), rs.getString("v")); });
        return m;
    }

    private void seedRandomFixture(UUID tenantId, UUID staffId, int patients) {
        inTenantTx(tenantId, () -> {
            jdbc.queryForObject("SELECT setseed(0.42)", Object.class);
            jdbc.update("""
                    INSERT INTO patients (tenant_id, name, phone, dob, gender)
                    SELECT ?, 'Patient ' || md5(n::text), '+9198' || lpad((n % (? * 9 / 10))::text, 8, '0'),
                           date '1950-01-01' + (n % 700), 'male' FROM generate_series(1, ?) n""", tenantId, patients, patients);
            jdbc.update("UPDATE patients SET status = 'merged', merged_into_id = (SELECT id FROM patients ORDER BY id LIMIT 1) " +
                    "WHERE id IN (SELECT id FROM patients WHERE random() < 0.01 ORDER BY id OFFSET 1 LIMIT 40)");
            // visits (a third no_show), each with an invoice in a random state
            jdbc.update("""
                    INSERT INTO queue_entries (tenant_id, patient_id, doctor_id, department_id, queue_date, token_number, status)
                    SELECT ?, id, ?, (SELECT id FROM departments WHERE is_default LIMIT 1), current_date - (random() * 90)::int, row_number() OVER (ORDER BY id),
                           CASE WHEN random() < 0.3 THEN 'no_show' ELSE 'completed' END
                    FROM patients WHERE random() < 0.4""", tenantId, staffId);
            jdbc.update("""
                    INSERT INTO invoices (tenant_id, invoice_number, queue_entry_id, patient_id, doctor_id, subtotal, tax, total, paid, status, created_by)
                    SELECT tenant_id, 'T-' || id, id, patient_id, doctor_id, 100, 0, 100,
                           CASE s WHEN 'paid' THEN 100 WHEN 'partial' THEN 40 ELSE 0 END, s, doctor_id
                    FROM (SELECT q.*, (ARRAY['paid','partial','unpaid','void'])[1 + (random() * 3)::int] AS s FROM queue_entries q WHERE tenant_id = ?) z
                    WHERE random() < 0.7""", tenantId);
            jdbc.update("""
                    INSERT INTO appointments (tenant_id, patient_id, doctor_id, start_time, end_time, status)
                    SELECT ?, id, ?, st, st + interval '30 minutes', CASE WHEN random() < 0.2 THEN 'completed' ELSE 'scheduled' END
                    FROM (SELECT id, now() + row_number() OVER (ORDER BY id) * interval '30 minutes' AS st FROM patients WHERE random() < 0.2) z""", tenantId, staffId);
            jdbc.update("""
                    INSERT INTO chronic_conditions (tenant_id, patient_id, condition, status, review_due_date, recorded_by)
                    SELECT ?, id, 'Cond', CASE WHEN random() < 0.3 THEN 'resolved' ELSE 'active' END, current_date + (random() * 60 - 30)::int, ?
                    FROM patients WHERE random() < 0.15""", tenantId, staffId);
            jdbc.update("""
                    INSERT INTO patient_allergies (tenant_id, patient_id, substance, severity, active, recorded_by)
                    SELECT ?, id, 'Sub' || (random() * 5)::int, (ARRAY['mild','moderate','severe'])[1 + (random() * 2)::int], random() < 0.8, ?
                    FROM patients WHERE random() < 0.15""", tenantId, staffId);
        });
    }

    @Test
    void backfillMatchesReferenceFieldByField_andReplayIsIdempotent() {
        SeededTenant t = seedTenant();
        SeededStaff doc = seedStaff(t, seedFullAccessRole(t.id()), "ops7a@a.com", "+919800070001", false);
        seedRandomFixture(t.id(), doc.id(), 5000);
        // the 5,000 inserts queued 5,000 dirty rows; drop them so only the backfill job populates the table
        inTenantTx(t.id(), () -> jdbc.update("DELETE FROM registry_dirty"));

        try (var c = superuserConnection(); var st = c.createStatement()) {
            // bulk-loaded in one transaction, so refresh stats the way autovacuum would in production
            st.execute("ANALYZE patients"); st.execute("ANALYZE queue_entries"); st.execute("ANALYZE invoices");
            st.execute("ANALYZE appointments"); st.execute("ANALYZE chronic_conditions"); st.execute("ANALYZE patient_allergies");
        } catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
        long t0 = System.nanoTime();
        long changed = reconciler.reconcileTenant(t.id());
        System.out.println("OPS-007 backfill of 5000 patients took " + (System.nanoTime() - t0) / 1_000_000 + " ms");

        Map<UUID, String> ref = inTenantTx(t.id(), () -> toMap(REFERENCE));
        Map<UUID, String> got = inTenantTx(t.id(), () -> toMap(SUMMARY));
        assertThat(ref).hasSize(5000);
        assertThat(got).as("exactly one row per patient").hasSize(5000);
        assertThat(changed).isEqualTo(5000);
        assertThat(got).isEqualTo(ref);
        assertThat(ref.values().stream().filter(v -> v.endsWith("|true|false")).count()).as("fixture has duplicates").isPositive();

        // idempotent: a second pass changes nothing and does not move change_seq
        long seqBefore = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT sum(change_seq) FROM patient_registry_summary", Long.class));
        assertThat(reconciler.reconcileTenant(t.id())).isZero();
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT sum(change_seq) FROM patient_registry_summary", Long.class))).isEqualTo(seqBefore);
    }

    @Test
    void committedChangesReachTheSummaryViaTheProjector() {
        SeededTenant t = seedTenant();
        SeededStaff doc = seedStaff(t, seedFullAccessRole(t.id()), "ops7b@a.com", "+919800070002", false);
        String token = loginAndGetAccessToken(doc);
        var created = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of("name", "Zed Summary",
                "phone", "+919822200001", "dob", "1990-01-01", "gender", "female")), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID pid = UUID.fromString((String) created.getBody().get("id"));

        projector.drain();
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT name_search FROM patient_registry_summary WHERE patient_id = ?", String.class, pid))).isEqualTo("zed summary");

        // an unpaid invoice (committed through its own transaction) shows as balance due after the next drain
        inTenantTx(t.id(), () -> {
            UUID q = UUID.randomUUID();
            jdbc.update("INSERT INTO queue_entries (id, tenant_id, patient_id, doctor_id, department_id, queue_date, token_number, status) VALUES (?,?,?,?,(SELECT id FROM departments WHERE is_default LIMIT 1),current_date,1,'completed')",
                    q, t.id(), pid, doc.id());
            jdbc.update("INSERT INTO invoices (tenant_id, queue_entry_id, patient_id, doctor_id, subtotal, tax, total, created_by) VALUES (?,?,?,?,500,0,500,?)",
                    t.id(), q, pid, doc.id(), doc.id());
        });
        SEEN.clear();
        projector.drain();
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT balance_due FROM patient_registry_summary WHERE patient_id = ?", java.math.BigDecimal.class, pid))).isEqualByComparingTo("500");
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT last_visit_date FROM patient_registry_summary WHERE patient_id = ?", java.sql.Date.class, pid))).isNotNull();
        assertThat(SEEN).anyMatch(e -> e.tenantId().equals(t.id()) && e.patientId().equals(pid) && e.changeSeq() > 0);

        // paying it clears the balance
        inTenantTx(t.id(), () -> jdbc.update("UPDATE invoices SET paid = total, status = 'paid' WHERE patient_id = ?", pid));
        projector.drain();
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT balance_due FROM patient_registry_summary WHERE patient_id = ?", java.math.BigDecimal.class, pid))).isEqualByComparingTo("0");

        // replaying the same change is a no-op: no event, same change_seq
        long seq = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT change_seq FROM patient_registry_summary WHERE patient_id = ?", Long.class, pid));
        SEEN.clear();
        inTenantTx(t.id(), () -> projector.project(t.id(), List.of(pid)));
        assertThat(SEEN).isEmpty();
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT change_seq FROM patient_registry_summary WHERE patient_id = ?", Long.class, pid))).isEqualTo(seq);
    }

    @Test
    void reconciliationRepairsDriftAndExportsTheGauge() {
        SeededTenant t = seedTenant();
        SeededStaff doc = seedStaff(t, seedFullAccessRole(t.id()), "ops7c@a.com", "+919800070003", false);
        seedRandomFixture(t.id(), doc.id(), 60);
        reconciler.reconcileTenant(t.id());
        inTenantTx(t.id(), () -> jdbc.update("DELETE FROM registry_dirty"));
        // simulate a projector failure: corrupt 3 rows, delete 2 (drift = 5)
        List<UUID> ids = inTenantTx(t.id(), () -> jdbc.queryForList("SELECT patient_id FROM patient_registry_summary ORDER BY patient_id LIMIT 5", UUID.class));
        inTenantTx(t.id(), () -> {
            for (UUID id : ids.subList(0, 3)) jdbc.update("UPDATE patient_registry_summary SET balance_due = 999, top_allergy = 'bogus' WHERE patient_id = ?", id);
            for (UUID id : ids.subList(3, 5)) jdbc.update("DELETE FROM patient_registry_summary WHERE patient_id = ?", id);
        });

        reconciler.runAll(); // all tenants, as the nightly job does
        assertThat(reconciler.driftRows()).isGreaterThanOrEqualTo(5); // other tenants' test data may add their own drift
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM patient_registry_summary", Long.class))).isEqualTo(60);
        assertThat(inTenantTx(t.id(), () -> toMap(SUMMARY))).isEqualTo(inTenantTx(t.id(), () -> toMap(REFERENCE)));
        reconciler.runAll();
        assertThat(reconciler.driftRows()).isZero(); // converged
    }

    @Test
    void summaryAndDirtyTablesAreForcedRlsAndTenantIsolated() throws Exception {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        SeededStaff docA = seedStaff(a, seedFullAccessRole(a.id()), "ops7d@a.com", "+919800070004", false);
        seedRandomFixture(a.id(), docA.id(), 20);
        reconciler.reconcileTenant(a.id());

        for (String tbl : List.of("patient_registry_summary", "registry_dirty")) {
            assertThat(jdbc.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE relname = ?", Boolean.class, tbl))
                    .as(tbl + " FORCE RLS").isTrue();
        }
        // nabd_app outside any tenant transaction sees nothing; tenant B sees none of A's rows and cannot write as A
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patient_registry_summary", Long.class)).isZero();
        assertThat(inTenantTx(b.id(), () -> jdbc.queryForObject("SELECT count(*) FROM patient_registry_summary", Long.class))).isZero();
        assertThat(inTenantTx(a.id(), () -> jdbc.queryForObject("SELECT count(*) FROM patient_registry_summary", Long.class))).isEqualTo(20);
        try {
            inTenantTx(b.id(), () -> jdbc.update("INSERT INTO patient_registry_summary (tenant_id, patient_id, change_seq) " +
                    "SELECT tenant_id, id, 1 FROM patients WHERE false UNION ALL SELECT ?, gen_random_uuid(), 1", a.id()));
            org.junit.jupiter.api.Assertions.fail("cross-tenant insert must be refused");
        } catch (org.springframework.dao.DataAccessException expected) {
            // RLS WITH CHECK (or the patients FK) refused it
        }
        assertThat(inTenantTx(b.id(), () -> jdbc.update("DELETE FROM patient_registry_summary WHERE tenant_id = ?", a.id()))).isZero();
        try (var c = superuserConnection(); var st = c.createStatement(); var rs = st.executeQuery(
                "SELECT count(*) FROM patient_registry_summary WHERE tenant_id = '" + a.id() + "'")) {
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(20);
        }
    }
}
