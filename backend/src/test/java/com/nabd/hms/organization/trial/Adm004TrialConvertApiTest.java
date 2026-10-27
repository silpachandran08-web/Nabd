package com.nabd.hms.organization.trial;

import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.patient.api.SyntheticPatients;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADM-004: "Start for real" purges demo data in the background, keeps configuration, stays behind the DPA gate; owner + step-up only. */
class Adm004TrialConvertApiTest extends ApiTestBase {

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    @Autowired
    private SyntheticPatients synthetic;

    private record Clinic(SeededTenant tenant, SeededStaff owner, SeededStaff doctor, String token) {
    }

    private Clinic trialClinic() {
        SeededTenant t = seedTenant();
        jdbc.update("UPDATE tenants SET data_gate = 'demo_only', status = 'trialing' WHERE id = ?", t.id());
        UUID role = seedFullAccessRole(t.id());
        SeededStaff owner = seedStaff(t, role, "own" + UUID.randomUUID() + "@a.com", "+9198" + (10000000 + (int) (Math.random() * 89999999)), false);
        SeededStaff doctor = seedStaff(t, role, "doc" + UUID.randomUUID() + "@a.com", "+9197" + (10000000 + (int) (Math.random() * 89999999)), false);
        su.update("UPDATE staff SET is_clinician = true WHERE id = ?", doctor.id());
        inTenantTx(t.id(), () -> {
            jdbc.update("INSERT INTO doctor_working_hours (tenant_id, doctor_id, day_of_week, start_time, end_time) VALUES (?,?,1,'09:00','13:00')", t.id(), doctor.id());
            jdbc.update("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount) VALUES (?, 'CONS', 'Consultation', 'Consultation', 300)", t.id());
        });
        return new Clinic(t, owner, doctor, loginAndGetAccessToken(owner));
    }

    private UUID demoPatient(String token, String name, String phone) {
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of("name", name, "phone", "+9197" + (10000000 + (int) (Math.random() * 89999999)),
                "dob", "1990-01-01", "gender", "female", "synthetic", true, "confirmedNotDuplicate", true)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    /** Queue entry, invoice with a line, appointment, chronic condition and allergy: dependants of one demo patient. */
    private void dependants(Clinic c, UUID patient) {
        UUID tid = c.tenant().id();
        UUID queueEntry = UUID.randomUUID();
        UUID invoice = UUID.randomUUID();
        Instant start = Instant.now().plus(5, ChronoUnit.DAYS).plus(1 + (int) (Math.random() * 20000), ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS); // distinct per patient: one doctor cannot overlap
        inTenantTx(tid, () -> {
            jdbc.update("INSERT INTO queue_entries (id, tenant_id, patient_id, doctor_id, department_id, queue_date, token_number, status) " +
                    "VALUES (?,?,?,?,(SELECT id FROM departments WHERE tenant_id = ? AND is_default),CURRENT_DATE,?,'waiting')", queueEntry, tid, patient, c.doctor().id(), tid, 1 + (int) (Math.random() * 1_000_000));
            jdbc.update("INSERT INTO invoices (id, tenant_id, queue_entry_id, patient_id, doctor_id, subtotal, tax, total, created_by) VALUES (?,?,?,?,?,100,0,100,?)",
                    invoice, tid, queueEntry, patient, c.doctor().id(), c.doctor().id());
            jdbc.update("INSERT INTO invoice_line_items (tenant_id, invoice_id, charge_code, charge_name, category, quantity, unit_price, tax_rate_percent, line_total, is_rx) " +
                    "VALUES (?,?,'CONS','Consultation','Consultation',1,100,0,100,false)", tid, invoice);
            jdbc.update("INSERT INTO appointments (tenant_id, patient_id, doctor_id, start_time, end_time) VALUES (?,?,?,?,?)",
                    tid, patient, c.doctor().id(), Timestamp.from(start), Timestamp.from(start.plus(15, ChronoUnit.MINUTES)));
            jdbc.update("INSERT INTO chronic_conditions (tenant_id, patient_id, condition, review_due_date, recorded_by) VALUES (?,?,'Diabetic',CURRENT_DATE,?)", tid, patient, c.owner().id());
            jdbc.update("INSERT INTO patient_allergies (tenant_id, patient_id, substance, severity, recorded_by) VALUES (?,?,'Penicillin','severe',?)", tid, patient, c.owner().id());
        });
    }

    private long count(UUID tenantId, String sql) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject(sql, Long.class));
    }

    private UUID challenge(Clinic c, String code) {
        su.update("DELETE FROM otp_send_counters WHERE tenant_id = ?", c.tenant().id()); // the 60 s resend gap would otherwise refuse a second code
        ResponseEntity<Map> r = exchange("/v1/setup/trial/convert/otp", HttpMethod.POST, authedJsonBody(c.token(), Map.of()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        su.update("UPDATE staff SET whatsapp_otp_hash = ? WHERE id = ?", OpaqueTokens.sha256Hex(code), c.owner().id());
        return UUID.fromString((String) r.getBody().get("otpChallengeId"));
    }

    private ResponseEntity<Map> convert(String token, UUID challenge, String otp, boolean confirm) {
        return exchange("/v1/setup/trial/convert", HttpMethod.POST,
                authedJsonBody(token, Map.of("confirm", confirm, "otpChallengeId", challenge.toString(), "otp", otp)), Map.class);
    }

    private Map<?, ?> waitDone(String token) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        Map<?, ?> status;
        do {
            status = exchange("/v1/setup/trial", HttpMethod.GET, authed(token), Map.class).getBody();
            Map<?, ?> conv = (Map<?, ?>) status.get("conversion");
            if (conv != null && ("done".equals(conv.get("status")) || "failed".equals(conv.get("status")))) {
                return status;
            }
            Thread.sleep(250);
        } while (System.currentTimeMillis() < deadline);
        return status;
    }

    @Test
    void startForRealDeletesEveryDemoRowAndKeepsTheSetup_dpaStillRequired() throws Exception {
        Clinic c = trialClinic();
        Clinic other = trialClinic(); // its demo data must survive
        UUID tid = c.tenant().id();
        UUID p1 = demoPatient(c.token(), "Demo Alpha", "+919800000101");
        UUID p2 = demoPatient(c.token(), "Demo Beta", "+919800000102");
        demoPatient(c.token(), "Demo Gamma", "+919800000103");
        dependants(c, p1);
        dependants(c, p2);
        demoPatient(other.token(), "Other Demo", "+919800000199");
        su.update("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?, 'Real One', '+919800000555', '1980-01-01', 'male')", tid);
        exchange("/v1/setup/policies", HttpMethod.GET, authed(c.token()), java.util.List.class); // seeds the policy defaults
        long policies = count(tid, "SELECT count(*) FROM clinic_policies");
        assertThat(count(tid, "SELECT count(*) FROM queue_entries")).isEqualTo(2);

        Map<?, ?> before = exchange("/v1/setup/trial", HttpMethod.GET, authed(c.token()), Map.class).getBody();
        assertThat(before.get("blockers")).isEqualTo(List.of("dpa", "plan"));

        ResponseEntity<Map> accepted = convert(c.token(), challenge(c, "123456"), "123456", true);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(accepted.getBody().get("conversionJobId")).isNotNull();

        Map<?, ?> done = waitDone(c.token());
        Map<?, ?> conv = (Map<?, ?>) done.get("conversion");
        assertThat(conv.get("status")).isEqualTo("done");
        assertThat(((Number) conv.get("syntheticRowsDeleted")).longValue()).isGreaterThanOrEqualTo(11); // 3 patients + 2 x (queue, invoice, line, appointment, condition, allergy)

        for (String table : List.of("queue_entries", "invoices", "invoice_line_items", "appointments", "chronic_conditions", "patient_allergies")) {
            assertThat(count(tid, "SELECT count(*) FROM " + table)).as(table).isZero();
        }
        assertThat(count(tid, "SELECT count(*) FROM patients WHERE is_synthetic")).isZero();
        assertThat(count(tid, "SELECT count(*) FROM patients WHERE name = 'Real One'")).as("a real patient is never touched").isEqualTo(1);
        assertThat(count(tid, "SELECT count(*) FROM doctor_working_hours")).as("schedules").isEqualTo(1);
        assertThat(count(tid, "SELECT count(*) FROM charge_catalogue")).as("price list").isEqualTo(1);
        assertThat(count(tid, "SELECT count(*) FROM clinic_policies")).as("policies").isEqualTo(policies);
        assertThat(count(tid, "SELECT count(*) FROM staff")).as("doctors and owner").isEqualTo(2);
        assertThat(count(other.tenant().id(), "SELECT count(*) FROM patients WHERE is_synthetic")).as("another clinic's demo data").isEqualTo(1);
        assertThat(count(other.tenant().id(), "SELECT count(*) FROM trial_conversion_jobs")).isZero();

        // audited with counts, no patient identifier
        String after = su.queryForObject("SELECT after::text FROM audit_log WHERE tenant_id = ? AND action = 'setup.trial_converted'", String.class, tid);
        assertThat(after).contains("syntheticRowsDeleted").doesNotContain("Demo").doesNotContain("+9198");

        // the data gate is untouched: a real patient is still refused until the DPA is signed and a plan chosen
        ResponseEntity<Map> real = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(),
                Map.of("name", "Real Two", "phone", "+919800000556", "dob", "1991-01-01", "gender", "female")), Map.class);
        assertThat(real.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(real.getBody().get("type"))).endsWith("DPA_REQUIRED");
        assertThat(((Map<?, ?>) exchange("/v1/setup/trial", HttpMethod.GET, authed(c.token()), Map.class).getBody()).get("blockers")).isEqualTo(List.of("dpa", "plan"));

        // a clinic converts once
        ResponseEntity<Map> again = convert(c.token(), challenge(c, "222222"), "222222", true);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(again.getBody().get("type"))).endsWith("ALREADY_CONVERTED");
    }

    @Test
    void refusals_notOwner_wrongCode_noConfirm_notOnTrial_nothingDeleted() {
        Clinic c = trialClinic();
        demoPatient(c.token(), "Keep Me", "+919800000201");
        UUID tid = c.tenant().id();

        UUID clerkRole = seedRole(tid, "Clerk", false, fullGrant("setup"), fullGrant("patients"));
        SeededStaff clerk = seedStaff(c.tenant(), clerkRole, "clerk" + UUID.randomUUID() + "@a.com", "+919800000209", false);
        String clerkToken = loginAndGetAccessToken(clerk);
        ResponseEntity<Map> notOwner = convert(clerkToken, UUID.randomUUID(), "000000", true);
        assertThat(notOwner.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(notOwner.getBody().get("type"))).endsWith("NOT_OWNER");

        UUID ch = challenge(c, "123456");
        assertThat(convert(c.token(), ch, "654321", true).getStatusCode()).as("wrong code").isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> noConfirm = convert(c.token(), ch, "123456", false);
        assertThat(noConfirm.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(noConfirm.getBody().get("type"))).endsWith("CONFIRM_REQUIRED");

        su.update("UPDATE tenants SET status = 'active' WHERE id = ?", tid);
        ResponseEntity<Map> notTrial = convert(c.token(), challenge(c, "333333"), "333333", true);
        assertThat(notTrial.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(notTrial.getBody().get("type"))).endsWith("ALREADY_CONVERTED");

        assertThat(count(tid, "SELECT count(*) FROM patients WHERE is_synthetic")).isEqualTo(1);
        assertThat(count(tid, "SELECT count(*) FROM trial_conversion_jobs")).isZero();
    }

    @Test
    void trialStatusShowsDaysLeftFromTheSubscription_andNoSubscriptionIsNone() {
        Clinic c = trialClinic();
        Map<?, ?> none = exchange("/v1/setup/trial", HttpMethod.GET, authed(c.token()), Map.class).getBody();
        assertThat(none.get("status")).isEqualTo("none");
        assertThat(none.get("daysLeft")).isNull();

        UUID plan = seedPlan("trial-" + c.tenant().slug(), 1000, "INR", 5);
        su.update("INSERT INTO master.subscriptions (tenant_id, plan_id, mrr_cents, currency, renewal_date, status, trial_ends_at) VALUES (?,?,1000,'INR',current_date + 30,'trialing', now() + interval '5 days 3 hours')",
                c.tenant().id(), plan);
        Map<?, ?> s = exchange("/v1/setup/trial", HttpMethod.GET, authed(c.token()), Map.class).getBody();
        assertThat(s.get("status")).isEqualTo("trialing");
        assertThat((Integer) s.get("daysLeft")).isBetween(5, 6);
        assertThat(s.get("endsAt")).isNotNull();
        assertThat(s.get("blockers")).isEqualTo(List.of("dpa", "plan")); // a trial subscription is not a chosen plan

        su.update("UPDATE master.subscriptions SET trial_ends_at = now() - interval '2 days' WHERE tenant_id = ?", c.tenant().id());
        assertThat(exchange("/v1/setup/trial", HttpMethod.GET, authed(c.token()), Map.class).getBody().get("daysLeft")).as("never negative").isEqualTo(0);
    }

    @Test
    void purgeBatchesBoundaryAndNothingLeftBehind() {
        Clinic c = trialClinic();
        UUID tid = c.tenant().id();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(demoPatient(c.token(), "Batch " + i, "+91980000030" + i));
        }
        dependants(c, ids.get(0));
        dependants(c, ids.get(4));
        int[] sizes = new int[3];
        for (int i = 0; i < 3; i++) {
            sizes[i] = inTenantTx(tid, () -> synthetic.purgeBatch(2).patients());
        }
        assertThat(sizes).containsExactly(2, 2, 1);
        assertThat(inTenantTx(tid, () -> synthetic.purgeBatch(2).patients())).isZero();
        assertThat(inTenantTx(tid, () -> synthetic.remaining())).isZero();
        assertThat(count(tid, "SELECT count(*) FROM invoices")).isZero();
    }
}
