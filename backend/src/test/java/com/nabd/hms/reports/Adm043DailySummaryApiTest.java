package com.nabd.hms.reports;

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
 * ADM-043: the owner's daily WhatsApp summary. Frozen on Tuesday 2026-03-10 in Asia/Kolkata; the owner's working hours end at
 * 21:00, so the clinic closes at 21:30. Figures come from the OPS-010 rollup (fed here through the projector) plus the live wait,
 * unbilled and day-close rows.
 */
@Import(Adm043DailySummaryApiTest.FrozenClock.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class Adm043DailySummaryApiTest extends ApiTestBase {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);
    private static final AtomicInteger SEQ = new AtomicInteger(4000);
    private static final String PATIENT_NAME = "Zephyrine Quenouille";

    @Autowired MutableClock clock;
    @Autowired DailySummaryJob job;
    @Autowired DailySummaryService service;
    @Autowired RollupProjector projector;
    @Autowired NotificationService notifications;
    @Autowired ObjectMapper json;

    @AfterEach
    void realTime() {
        clock.reset();
    }

    private record Clinic(SeededTenant tenant, SeededStaff owner, String ownerPhone, String token, String patientPhone) {
    }

    private void at(String time) {
        clock.setLocal(DAY + "T" + time, ZONE.getId());
    }

    private static Instant local(String time) {
        return DAY.atTime(java.time.LocalTime.parse(time)).atZone(ZONE).toInstant();
    }

    /** A Kolkata clinic whose owner (also the doctor) works 09:00-21:00 on Tuesdays and has an approved daily_summary template. */
    private Clinic clinic() {
        SeededTenant tenant = seedTenant();
        jdbc.update("UPDATE tenants SET timezone = ? WHERE id = ?", ZONE.getId(), tenant.id());
        int n = SEQ.incrementAndGet();
        String ownerPhone = "+9194" + (3000000 + n);
        SeededStaff owner = seedStaff(tenant, seedFullAccessRole(tenant.id()), "ds" + n + "@a.com", ownerPhone, false);
        String token = loginAndGetAccessToken(owner); // real time first: the JWT must be valid for the decoder
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

    private void settings(Clinic c, boolean enabled, boolean combined, String sendAt) {
        ResponseEntity<Map> r = exchange("/v1/reports/daily-summary/settings", HttpMethod.PUT, authedJsonBody(c.token(), Map.of(
                "enabled", enabled, "combined", combined, "recipients", List.of(Map.of("staffId", c.owner().id().toString())), "sendAt", sendAt)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** A walk-in seen at 10:00-10:14 and, when billed, paid 500 in cash at 10:30; timestamps are pinned to the frozen day. */
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
            rollup(c, Map.of("queueDate", DAY.toString()));
        }
        return entry;
    }

    /** Feeds the reporting-rollup consumer, which recomputes the frozen day from source. */
    private void rollup(Clinic c, Map<String, Object> data) {
        inTenantTx(c.tenant().id(), () -> projector.handle(new EventConsumer.Event("ev:in:x", "1-0", UUID.randomUUID().toString(), "visit.completed.v1",
                c.tenant().id(), null, 1, json.valueToTree(data))));
    }

    private List<UUID> requests(Clinic c) {
        return inTenantTx(c.tenant().id(), () -> jdbc.queryForList("SELECT id FROM notification_requests WHERE purpose = 'daily_summary' ORDER BY created_at", UUID.class));
    }

    private List<String> sentParams(Clinic c) {
        List<UUID> ids = requests(c);
        assertThat(ids).hasSize(1);
        NotificationClaim claim = notifications.claim(ids.get(0));
        assertThat(claim.recipient().phoneE164()).isEqualTo(c.ownerPhone());
        return claim.params();
    }

    @Test
    void ac1_sentAtCloseTime_withVisitsCollectionsNoShowsAndWait_onceOnly() {
        Clinic c = clinic();
        settings(c, true, false, "close");
        visit(c, true);
        at("21:29");
        assertThat(job.run()).isZero();
        assertThat(requests(c)).isEmpty();
        at("21:31");
        assertThat(job.run()).isEqualTo(1);
        List<String> p = sentParams(c);
        assertThat(p).hasSize(4);
        assertThat(p.get(0)).isEqualTo("Tue 10 Mar 2026");
        assertThat(p.get(1)).contains("1 visits (").contains("cash INR 500.00").contains("UPI INR 0.00").contains("card INR 0.00")
                .contains("0 no-shows").contains("avg wait 14 min");
        assertThat(p.get(3)).endsWith("/overview");
        at("22:00");
        assertThat(job.run()).as("sent once per day").isZero();
        assertThat(requests(c)).hasSize(1);
    }

    @Test
    void ac2_messageCarriesNoPatientNamePhoneOrClinicalContent() {
        Clinic c = clinic();
        settings(c, true, false, "close");
        visit(c, false); // an unbilled consultation: the one place a patient could leak into the attention line
        rollup(c, Map.of("queueDate", DAY.toString()));
        at("21:31");
        assertThat(job.run()).isEqualTo(1);
        for (String param : sentParams(c)) {
            assertThat(param).doesNotContain(PATIENT_NAME).doesNotContain("Zephyrine").doesNotContain("Quenouille").doesNotContain(c.patientPhone())
                    .doesNotContain(c.patientPhone().substring(3)).doesNotContain("\n").doesNotContainIgnoringCase("diagnos");
        }
        Map preview = exchange("/v1/reports/daily-summary/preview", HttpMethod.GET, authed(c.token()), Map.class).getBody();
        assertThat(preview.get("params").toString()).doesNotContain(PATIENT_NAME).doesNotContain(c.patientPhone());
        assertThat(((List<String>) preview.get("params")).get(2)).contains("1 consultation done with no bill");
    }

    @Test
    void ac3_combinedMessageListsEachBranchAndTheTotal_oneMessageOnly() {
        Clinic c = clinic();
        SeededBrand brand = seedBrand(seedOwner("chain" + SEQ.incrementAndGet() + "@a.com"), "Chain " + SEQ.get());
        jdbc.update("UPDATE tenants SET brand_id = ?, name = 'Alpha Branch' WHERE id = ?", brand.id(), c.tenant().id());
        Clinic b = clinic();
        Clinic d = clinic();
        jdbc.update("UPDATE tenants SET brand_id = ?, name = 'Beta Branch' WHERE id = ?", brand.id(), b.tenant().id());
        jdbc.update("UPDATE tenants SET brand_id = ?, name = 'Gamma Branch' WHERE id = ?", brand.id(), d.tenant().id());
        settings(c, true, true, "close");
        Map got = exchange("/v1/reports/daily-summary/settings", HttpMethod.GET, authed(c.token()), Map.class).getBody();
        assertThat(got.get("branches")).isEqualTo(3);
        assertThat(got.get("combined")).isEqualTo(true);
        visit(c, true);
        visit(b, true);
        at("21:31");
        assertThat(job.run()).isEqualTo(1);
        String figures = sentParams(c).get(1);
        assertThat(figures).contains("Alpha Branch: 1 visits, INR 500.00 collected").contains("Beta Branch: 1 visits, INR 500.00 collected")
                .contains("Gamma Branch: 0 visits").contains("Total: 2 visits, INR 1000.00 (cash INR 1000.00");
        assertThat(requests(b)).as("the sibling clinics did not opt in: nothing from them").isEmpty();
        assertThat(requests(d)).isEmpty();
    }

    /** T20-BUG-3: 'combined' ticked in two sibling clinics is still one chain message per brand and day. */
    @Test
    void combinedInTwoSiblings_sendsTheChainSummaryOnce_perBrandAndDay() {
        Clinic a = clinic();
        SeededBrand brand = seedBrand(seedOwner("chain" + SEQ.incrementAndGet() + "@a.com"), "Chain " + SEQ.get());
        Clinic b = clinic();
        jdbc.update("UPDATE tenants SET brand_id = ?, name = 'Alpha Branch' WHERE id = ?", brand.id(), a.tenant().id());
        jdbc.update("UPDATE tenants SET brand_id = ?, name = 'Beta Branch' WHERE id = ?", brand.id(), b.tenant().id());
        settings(a, true, true, "close");
        settings(b, true, true, "close");
        visit(a, true);
        visit(b, true);
        at("21:31");
        assertThat(job.run()).as("one chain message per brand").isEqualTo(1);
        assertThat(requests(a).size() + requests(b).size()).isEqualTo(1);
        assertThat(job.run()).as("the other sibling's day is marked done, nothing later").isZero();
        assertThat(requests(a).size() + requests(b).size()).isEqualTo(1);
    }

    @Test
    void ac4_notOptedIn_nothingIsSent_andOnlyAnOwnerMayChangeTheSettings() {
        Clinic c = clinic();
        visit(c, true);
        at("21:31");
        assertThat(job.run()).isZero();
        settings(c, false, false, "close");
        assertThat(job.run()).isZero();
        assertThat(requests(c)).isEmpty();
        // a doctor with reports:view is not the owner: 403, and the owner cannot name a non-owner recipient: 400
        int n = SEQ.incrementAndGet();
        SeededStaff doctor = seedStaff(c.tenant(), seedRole(c.tenant().id(), "Doctor", false, new com.nabd.hms.common.ModuleGrant("reports", true, false, false, false, false, false, false)),
                "dd" + n + "@a.com", "+9194" + (3000000 + n), false);
        clock.reset();
        String doctorToken = loginAndGetAccessToken(doctor);
        assertThat(exchange("/v1/reports/daily-summary/settings", HttpMethod.GET, authed(doctorToken), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<String> bad = exchange("/v1/reports/daily-summary/settings", HttpMethod.PUT, authedJsonBody(c.token(), Map.of(
                "enabled", true, "combined", false, "recipients", List.of(Map.of("staffId", doctor.id().toString())), "sendAt", "close")), String.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody()).contains("recipient-not-owner");
    }

    @Test
    void ac5_unbilledConsultationsShowCountAmountAndLink_andDayCloseSendsEarlier() {
        Clinic c = clinic();
        settings(c, true, false, "close");
        String entry = visit(c, false);
        inTenantTx(c.tenant().id(), () -> jdbc.update("""
                INSERT INTO procedure_orders (tenant_id, queue_entry_id, patient_id, ordered_by, charge_code, charge_name, base_amount, status, billed, completed_at)
                SELECT tenant_id, id, patient_id, ?, 'DRS', 'Dressing', 300, 'completed', true, ? FROM queue_entries WHERE id = ?::uuid
                """, c.owner().id(), Timestamp.from(local("10:20")), entry));
        rollup(c, Map.of("queueDate", DAY.toString()));
        inTenantTx(c.tenant().id(), () -> jdbc.update("INSERT INTO day_closes (tenant_id, close_date, status, expected_cash, counted_cash, variance, closed_by, closed_at) VALUES (?,?,'closed',0,0,0,?,?)",
                c.tenant().id(), java.sql.Date.valueOf(DAY), c.owner().id(), Timestamp.from(local("18:00"))));
        at("17:59");
        assertThat(job.run()).isZero();
        at("18:05");
        assertThat(job.run()).as("the day close came before the configured close").isEqualTo(1);
        List<String> p = sentParams(c);
        assertThat(p.get(2)).isEqualTo("1 consultation done with no bill (INR 300.00 at risk)");
        assertThat(p.get(3)).endsWith("/overview");
    }

    @Test
    void lateRollup_waitsUpToAnHour_thenSendsMarkedPartial() {
        Clinic c = clinic();
        settings(c, true, false, "20:00");
        visit(c, true);
        inTenantTx(c.tenant().id(), () -> jdbc.update("DELETE FROM tenant_daily_metrics WHERE tenant_id = ?", c.tenant().id())); // the rollup never arrived
        at("20:30");
        assertThat(job.run()).as("stale rollup inside the grace period: wait").isZero();
        at("21:01");
        assertThat(job.run()).isEqualTo(1);
        List<String> p = sentParams(c);
        assertThat(p.get(0)).startsWith("Tue 10 Mar 2026 (partial");
        assertThat(p.get(1)).contains("0 visits");
    }

    static class FrozenClock {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}
