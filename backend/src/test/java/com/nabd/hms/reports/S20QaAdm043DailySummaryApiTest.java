package com.nabd.hms.reports;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.messaging.NotificationClaim;
import com.nabd.hms.messaging.NotificationService;
import com.nabd.hms.shared.events.consumer.EventConsumer;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S20 QA (independent): ADM-043 edges: PHI with clinical note content present, opt-out after opt-in, grace then no resend,
 * combined enabled in two siblings, a phoneless recipient, role gates, input bounds, tenant isolation, fixed time once-only.
 * Frozen on Tuesday 2026-03-10 Asia/Kolkata, working hours end 21:00 (clinic close 21:30), like the engineer's test.
 */
@Import(S20QaAdm043DailySummaryApiTest.FrozenClock.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class S20QaAdm043DailySummaryApiTest extends ApiTestBase {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);
    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 200_000) + 5000);
    private static final String PATIENT_NAME = "Zephyrine Quenouille";
    private static final String DIAGNOSIS = "Pulmonary tuberculosis";

    @Autowired MutableClock clock;
    @Autowired DailySummaryJob job;
    @Autowired RollupProjector projector;
    @Autowired DailySummaryService service;
    @Autowired NotificationService notifications;
    @Autowired ObjectMapper json;

    @AfterEach
    void realTime() { clock.reset(); }

    private record Clinic(SeededTenant tenant, SeededStaff owner, String ownerPhone, String token, String patientPhone) { }

    private void at(String time) { clock.setLocal(DAY + "T" + time, ZONE.getId()); }

    private static Instant local(String time) { return DAY.atTime(java.time.LocalTime.parse(time)).atZone(ZONE).toInstant(); }

    private Clinic clinic() {
        clock.reset();
        SeededTenant tenant = seedTenant();
        jdbc.update("UPDATE tenants SET timezone = ? WHERE id = ?", ZONE.getId(), tenant.id());
        int n = SEQ.incrementAndGet();
        String ownerPhone = "+9194" + (3000000 + n);
        SeededStaff owner = seedStaff(tenant, seedFullAccessRole(tenant.id()), "qa" + n + "@a.com", ownerPhone, false);
        String token = loginAndGetAccessToken(owner);
        inTenantTx(tenant.id(), () -> {
            jdbc.update("INSERT INTO doctor_working_hours (id, tenant_id, doctor_id, day_of_week, start_time, end_time, slot_minutes, max_patients) VALUES (?,?,?,2,'09:00','21:00',15,NULL)",
                    UUID.randomUUID(), tenant.id(), owner.id());
            jdbc.update("""
                    INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status)
                    VALUES (?, 'daily_summary', ?, 'en', 'UTILITY', 'Nabd', 'Clinic summary for {{1}}: {{2}}. Attention: {{3}}. Open {{4}} for details.', 4, 'approved')
                    """, tenant.id(), "daily_summary_" + tenant.id().toString().substring(0, 8));
        });
        return new Clinic(tenant, owner, ownerPhone, token, "+91996" + (2000000 + n));
    }

    private ResponseEntity<Map> put(Clinic c, Object body) {
        return exchange("/v1/reports/daily-summary/settings", HttpMethod.PUT, authedJsonBody(c.token(), body), Map.class);
    }

    private void settings(Clinic c, boolean enabled, boolean combined, String sendAt, UUID... recipients) {
        List<Map<String, String>> r = java.util.Arrays.stream(recipients.length == 0 ? new UUID[] {c.owner().id()} : recipients).map(id -> Map.of("staffId", id.toString())).toList();
        ResponseEntity<Map> res = put(c, Map.of("enabled", enabled, "combined", combined, "recipients", r, "sendAt", sendAt));
        assertThat(res.getStatusCode()).as("%s", res.getBody()).isEqualTo(HttpStatus.OK);
    }

    private String visit(Clinic c, boolean bill) {
        at("10:00");
        String patient = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "name", PATIENT_NAME, "phone", c.patientPhone(), "dob", "1971-01-01", "gender", "female")), Map.class).getBody().get("id");
        String entry = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of("patientId", patient, "doctorId", c.owner().id().toString())), Map.class).getBody().get("id");
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending")) {
            exchange("/v1/queue/" + entry + "/status", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("status", s)), Map.class);
        }
        inTenantTx(c.tenant().id(), () -> {
            jdbc.update("UPDATE queue_entries SET created_at = ? WHERE id = ?::uuid", Timestamp.from(local("10:00")), entry);
            jdbc.update("UPDATE queue_entry_events SET occurred_at = ? WHERE queue_entry_id = ?::uuid AND to_status = 'in_consult'", Timestamp.from(local("10:14")), entry);
        });
        if (bill) {
            String invoice = (String) exchange("/v1/billing/checkout/" + entry, HttpMethod.POST, authedJsonBody(c.token(), Map.of("lineItems", List.of(Map.of(
                    "override", Map.of("reasonCode", "other"), "chargeCode", "X", "chargeName", "X", "category", "Service", "quantity", 1, "unitPrice", 500, "taxRatePercent", 0)))), Map.class).getBody().get("id");
            exchange("/v1/billing/invoices/" + invoice + "/payments", HttpMethod.POST, authedJsonBody(c.token(), Map.of("method", "cash", "amount", 500)), Map.class);
            inTenantTx(c.tenant().id(), () -> {
                jdbc.update("UPDATE invoices SET created_at = ? WHERE id = ?::uuid", Timestamp.from(local("10:30")), invoice);
                jdbc.update("UPDATE invoice_payments SET recorded_at = ? WHERE invoice_id = ?::uuid", Timestamp.from(local("10:30")), invoice);
            });
        }
        rollup(c);
        return entry;
    }

    private void rollup(Clinic c) {
        inTenantTx(c.tenant().id(), () -> projector.handle(new EventConsumer.Event("ev:in:x", "1-0", UUID.randomUUID().toString(), "visit.completed.v1",
                c.tenant().id(), null, 1, json.valueToTree(Map.of("queueDate", DAY.toString())))));
    }

    private List<UUID> requests(Clinic c) {
        return inTenantTx(c.tenant().id(), () -> jdbc.queryForList("SELECT id FROM notification_requests WHERE purpose = 'daily_summary' ORDER BY created_at", UUID.class));
    }

    private NotificationClaim claim(Clinic c, UUID id) {
        return notifications.claim(id);
    }

    @Test
    void dApi01_noPatientNamePhoneDiagnosisOrAllergyInTheMessage_evenWithClinicalContentOnFile() {
        Clinic c = clinic();
        settings(c, true, false, "close");
        String entry = visit(c, false);
        ResponseEntity<Map> note = exchange("/v1/clinical/notes/" + entry, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of(
                "subjective", "cough 3 weeks", "assessment", DIAGNOSIS, "plan", "Rifampicin", "diagnosis", DIAGNOSIS)), Map.class);
        assertThat(note.getStatusCode()).as("%s", note.getBody()).isEqualTo(HttpStatus.OK);
        rollup(c);
        at("21:31");
        assertThat(job.run()).isEqualTo(1);
        List<UUID> ids = requests(c);
        assertThat(ids).hasSize(1);
        NotificationClaim cl = claim(c, ids.get(0));
        assertThat(cl.recipient().phoneE164()).isEqualTo(c.ownerPhone());
        String all = String.join(" | ", cl.params());
        assertThat(all).doesNotContain("Zephyrine").doesNotContain("Quenouille").doesNotContain(c.patientPhone().substring(3)).doesNotContainIgnoringCase("tuberculosis")
                .doesNotContainIgnoringCase("Rifampicin").doesNotContain("\n").doesNotContain(entry);
        assertThat(cl.params().get(2)).contains("1 consultation done with no bill");
        assertThat(cl.params().get(3)).startsWith("http").endsWith("/overview").doesNotContain("token");
        // the stored request row carries no patient data either
        String row = inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT row_to_json(n)::text FROM notification_requests n WHERE id = ?", String.class, ids.get(0)));
        assertThat(row).doesNotContain("Zephyrine").doesNotContainIgnoringCase("tuberculosis");
    }

    @Test
    void dApi02_09_optOutAfterOptInSendsNothing_fixedTimeSendsOnceOnly() {
        Clinic c = clinic();
        visit(c, true);
        settings(c, true, false, "20:00");
        at("19:59");
        assertThat(job.run()).isZero();
        settings(c, false, false, "20:00");
        at("20:01");
        assertThat(job.run()).as("opted out: nothing").isZero();
        assertThat(requests(c)).isEmpty();
        settings(c, true, false, "20:00");
        assertThat(job.run()).isEqualTo(1);
        at("23:30");
        assertThat(job.run()).isZero();
        // a settings save later the same day does not reopen the day
        settings(c, true, false, "23:40");
        at("23:45");
        assertThat(job.run()).as("once per clinic day").isZero();
        assertThat(requests(c)).hasSize(1);
    }

    @Test
    void dApi03_lateRollup_waitsUpToAnHour_sendsPartialOnce_noResendWhenTheRollupArrives() {
        Clinic c = clinic();
        settings(c, true, false, "close");
        visit(c, true);
        inTenantTx(c.tenant().id(), () -> jdbc.update("DELETE FROM tenant_daily_metrics WHERE tenant_id = ?", c.tenant().id()));
        at("21:31");
        assertThat(job.run()).isZero();
        at("22:29");
        assertThat(job.run()).as("inside the grace hour").isZero();
        at("22:31");
        assertThat(job.run()).isEqualTo(1);
        List<UUID> ids = requests(c);
        assertThat(ids).hasSize(1);
        assertThat(claim(c, ids.get(0)).params().get(0)).contains("(partial");
        rollup(c);
        at("23:00");
        assertThat(job.run()).as("the late rollup does not trigger a second, corrected message").isZero();
        assertThat(requests(c)).hasSize(1);
    }

    @Test
    void dApi04_combinedEnabledInTwoSiblingClinics_eachSendsAFullCombinedMessage() {
        Clinic a = clinic();
        SeededBrand brand = seedBrand(seedOwner("qchain" + SEQ.incrementAndGet() + "@a.com"), "QChain " + SEQ.get());
        Clinic b = clinic();
        jdbc.update("UPDATE tenants SET brand_id = ?, name = 'Alpha Branch' WHERE id = ?", brand.id(), a.tenant().id());
        jdbc.update("UPDATE tenants SET brand_id = ?, name = 'Beta Branch' WHERE id = ?", brand.id(), b.tenant().id());
        settings(a, true, true, "close");
        settings(b, true, true, "close");
        visit(a, true);
        visit(b, false);
        at("21:31");
        int sent = job.run();
        assertThat(requests(a)).hasSize(1);
        String fa = claim(a, requests(a).get(0)).params().get(1);
        assertThat(fa).contains("Alpha Branch: 1 visits, INR 500.00 collected").contains("Beta Branch: 1 visits, INR 0.00 collected").contains("Total: 2 visits, INR 500.00");
        // Documented: each clinic's own row sends, so an owner who ticked "combined" in both branches gets the same chain summary twice.
        assertThat(sent).as("two sibling clinics both combined").isEqualTo(2);
        assertThat(requests(b)).hasSize(1);
        assertThat(claim(b, requests(b).get(0)).params().get(1)).contains("Alpha Branch").contains("Beta Branch").contains("Total: 2 visits");
        // the combined attention line counts the sibling's unbilled consult too
        assertThat(claim(a, requests(a).get(0)).params().get(2)).contains("1 consultation done with no bill");
    }

    @Test
    void dApi05_aRecipientWithoutAPhoneIsSkipped_theOtherOwnerStillGetsIt_andTheJobDoesNotFail() {
        Clinic c = clinic();
        int n = SEQ.incrementAndGet();
        UUID ownerRole = c.owner().roleId();
        SeededStaff phoneless = seedStaff(c.tenant(), ownerRole, "np" + n + "@a.com", null, false);
        inTenantTx(c.tenant().id(), () -> jdbc.update("UPDATE staff SET mobile_phone = NULL WHERE id = ?", phoneless.id()));
        ResponseEntity<Map> got = exchange("/v1/reports/daily-summary/settings", HttpMethod.GET, authed(c.token()), Map.class);
        List<Map<String, Object>> cand = (List<Map<String, Object>>) got.getBody().get("candidates");
        assertThat(cand).extracting(m -> m.get("staffId")).contains(c.owner().id().toString(), phoneless.id().toString());
        assertThat(cand.stream().filter(m -> phoneless.id().toString().equals(m.get("staffId"))).findFirst().get()).containsEntry("hasPhone", false);
        settings(c, true, false, "close", c.owner().id(), phoneless.id());
        visit(c, true);
        at("21:31");
        assertThat(job.run()).isEqualTo(1);
        List<UUID> ids = requests(c);
        assertThat(ids).hasSize(1);
        assertThat(claim(c, ids.get(0)).recipient().phoneE164()).isEqualTo(c.ownerPhone());

        // only phoneless recipients: nothing goes out and the day is not marked sent (retried each run, silently)
        Clinic d = clinic();
        SeededStaff np2 = seedStaff(d.tenant(), d.owner().roleId(), "np" + SEQ.incrementAndGet() + "@a.com", null, false);
        inTenantTx(d.tenant().id(), () -> jdbc.update("UPDATE staff SET mobile_phone = NULL WHERE id = ?", np2.id()));
        settings(d, true, false, "close", np2.id());
        visit(d, true);
        at("21:31");
        assertThat(job.run()).isZero();
        assertThat(requests(d)).isEmpty();
        assertThat(inTenantTx(d.tenant().id(), () -> jdbc.queryForObject("SELECT last_sent_day IS NULL FROM daily_summary_settings WHERE tenant_id = ?", Boolean.class, d.tenant().id()))).isTrue();
    }

    @Test
    void dApi06_07_roleGates_andInputBounds() {
        Clinic c = clinic();
        int n = SEQ.incrementAndGet();
        SeededStaff doctor = seedStaff(c.tenant(), seedRole(c.tenant().id(), "Doctor", false, new ModuleGrant("reports", true, true, true, false, false, false, false),
                new ModuleGrant("clinical", true, true, true, false, false, false, false)), "qd" + n + "@a.com", "+9194" + (3000000 + n), false);
        SeededStaff desk = seedStaff(c.tenant(), seedRole(c.tenant().id(), "Desk", false, new ModuleGrant("patients", true, true, true, false, false, false, false)), "qk" + n + "@a.com", "+9195" + (3000000 + n), false);
        String doc = loginAndGetAccessToken(doctor);
        String dsk = loginAndGetAccessToken(desk);
        for (String tok : List.of(doc, dsk)) {
            assertThat(exchange("/v1/reports/daily-summary/settings", HttpMethod.GET, authed(tok), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exchange("/v1/reports/daily-summary/preview", HttpMethod.GET, authed(tok), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exchange("/v1/reports/daily-summary/settings", HttpMethod.PUT, authedJsonBody(tok, Map.of("enabled", true, "combined", false, "recipients", List.of(Map.of("staffId", c.owner().id().toString())), "sendAt", "close")), String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(exchange("/v1/reports/daily-summary/settings", HttpMethod.GET, null, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM daily_summary_settings WHERE tenant_id = ?", Integer.class, c.tenant().id()))).isZero();

        List<Map<String, String>> me = List.of(Map.of("staffId", c.owner().id().toString()));
        assertThat(put(c, Map.of("enabled", true, "combined", false, "recipients", me, "sendAt", "25:00")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(c, Map.of("enabled", true, "combined", false, "recipients", me, "sendAt", "7pm")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(c, Map.of("enabled", true, "combined", false, "recipients", List.of(Map.of("staffId", doctor.id().toString())), "sendAt", "close")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(c, Map.of("enabled", true, "combined", false, "recipients", List.of(Map.of("staffId", UUID.randomUUID().toString())), "sendAt", "close")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Clinic other = clinic();
        assertThat(put(c, Map.of("enabled", true, "combined", false, "recipients", List.of(Map.of("staffId", other.owner().id().toString())), "sendAt", "close")).getStatusCode()).as("another tenant's owner").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(c, Map.of("enabled", true, "combined", false, "sendAt", "close")).getStatusCode()).as("recipients missing").isEqualTo(HttpStatus.BAD_REQUEST);
        // enabled with no recipients is accepted and simply never sends (documented)
        ResponseEntity<Map> none = put(c, Map.of("enabled", true, "combined", false, "recipients", List.of(), "sendAt", "close"));
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.OK);
        visit(c, true);
        at("21:31");
        assertThat(job.run()).isZero();
        // a 21-recipient list is refused
        List<Map<String, String>> many = new java.util.ArrayList<>();
        for (int i = 0; i < 21; i++) many.add(Map.of("staffId", c.owner().id().toString()));
        assertThat(put(c, Map.of("enabled", true, "combined", false, "recipients", many, "sendAt", "close")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // sendAt omitted = close, and an audit row exists
        assertThat(put(c, Map.of("enabled", true, "combined", false, "recipients", me)).getBody()).containsEntry("sendAt", "close");
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'daily_summary.settings' AND actor_id = ?", Integer.class, c.owner().id()))).isGreaterThanOrEqualTo(2);
    }

    @Test
    void dApi08_tenantIsolation_anotherOwnerSeesOnlyTheirOwnSettings_andPreviewIsPerClinic() {
        Clinic a = clinic();
        Clinic b = clinic();
        settings(a, true, false, "20:15");
        visit(a, true);
        Map bs = exchange("/v1/reports/daily-summary/settings", HttpMethod.GET, authed(b.token()), Map.class).getBody();
        assertThat(bs).containsEntry("enabled", false).containsEntry("sendAt", "close").containsEntry("branches", 1);
        assertThat((List<?>) bs.get("recipients")).isEmpty();
        assertThat(((List<Map<String, Object>>) bs.get("candidates"))).extracting(m -> m.get("staffId")).containsExactly(b.owner().id().toString());
        at("12:00");
        Map pb = exchange("/v1/reports/daily-summary/preview", HttpMethod.GET, authed(b.token()), Map.class).getBody();
        assertThat(((List<String>) pb.get("params")).get(1)).contains("0 visits");
        Map pa = exchange("/v1/reports/daily-summary/preview", HttpMethod.GET, authed(a.token()), Map.class).getBody();
        assertThat(((List<String>) pa.get("params")).get(1)).contains("1 visits").contains("cash INR 500.00");
        settings(b, false, false, "close");
        Map as = exchange("/v1/reports/daily-summary/settings", HttpMethod.GET, authed(a.token()), Map.class).getBody();
        assertThat(as).containsEntry("enabled", true).containsEntry("sendAt", "20:15");
        at("20:16");
        assertThat(job.run()).isEqualTo(1);
        assertThat(requests(a)).hasSize(1);
        assertThat(requests(b)).isEmpty();
    }

    @Test
    void dApi10_closeWithNoWorkingHoursThatWeekday_neverSends_butADayCloseDoes() {
        Clinic c = clinic();
        inTenantTx(c.tenant().id(), () -> jdbc.update("DELETE FROM doctor_working_hours WHERE tenant_id = ?", c.tenant().id()));
        settings(c, true, false, "close");
        visit(c, true);
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM doctor_working_hours WHERE tenant_id = ?", Integer.class, c.tenant().id()))).isZero();
        at("23:59");
        java.util.Optional<Instant> due = inTenantTx(c.tenant().id(), () -> service.dueAt(c.tenant().id(), DAY, "close"));
        job.run(); // the count covers every live tenant in this context, so the assertion is on this clinic's requests
        assertThat(due).isEmpty();
        assertThat(requests(c)).as("engineer-disclosed: no session end that weekday, nothing is due").isEmpty();
        inTenantTx(c.tenant().id(), () -> jdbc.update("INSERT INTO day_closes (tenant_id, close_date, status, expected_cash, counted_cash, variance, closed_by, closed_at) VALUES (?,?,'closed',0,0,0,?,?)",
                c.tenant().id(), java.sql.Date.valueOf(DAY), c.owner().id(), Timestamp.from(local("22:00"))));
        job.run();
        assertThat(requests(c)).hasSize(1);
    }

    static class FrozenClock {
        @Bean
        @Primary
        MutableClock mutableClock() { return new MutableClock(); }
    }
}
