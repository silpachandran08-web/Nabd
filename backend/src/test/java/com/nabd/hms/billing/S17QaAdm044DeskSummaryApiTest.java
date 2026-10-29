package com.nabd.hms.billing;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** S17 QA (independent): ADM-044 GET /v1/reports/desk-summary against the real API and Postgres. */
@SuppressWarnings({"rawtypes", "unchecked"})
class S17QaAdm044DeskSummaryApiTest extends ApiTestBase {

    private static final JdbcTemplate SU = new JdbcTemplate(superuserDataSource());

    private record Clinic(SeededTenant tenant, SeededStaff owner, String token) {
    }

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String phone() {
        return "+9196" + (10000000 + (int) (Math.random() * 89999999));
    }

    /** A fresh clinic; tz/dayStart are set as superuser before any request reads (and caches) the clinic clock. */
    private Clinic clinic(String tz, int dayStartMinute) {
        SeededTenant t = seedTenant();
        if (tz != null) SU.update("UPDATE tenants SET timezone = ?, day_start_minute = ? WHERE id = ?", tz, dayStartMinute, t.id());
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "qa44o" + uid() + "@a.com", phone(), false);
        return new Clinic(t, owner, loginAndGetAccessToken(owner));
    }

    private String receptionist(Clinic c) {
        UUID role = seedRole(c.tenant().id(), "Reception" + uid(), false, new ModuleGrant("queue", true, false, false, false, false, false, false));
        return loginAndGetAccessToken(seedStaff(c.tenant(), role, "qa44r" + uid() + "@a.com", phone(), false));
    }

    private int born;

    private String patient(Clinic c, String name) {
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "name", name, "phone", phone(), "dob", LocalDate.of(1960, 1, 1).plusDays(331L * born++).toString(), "gender", "female")), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private String visitAtCheckout(Clinic c, String patientId) {
        ResponseEntity<Map> r = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "patientId", patientId, "doctorId", c.owner().id().toString())), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("%s", r.getBody()).isTrue();
        String q = (String) r.getBody().get("id");
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending")) {
            exchange("/v1/queue/" + q + "/status", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("status", s)), Map.class);
        }
        return q;
    }

    /** Bills a free-text line and records the given payments [method, amount]...; returns the invoice id. */
    private String bill(Clinic c, String queueEntryId, int amount, Object... payments) {
        ResponseEntity<Map> inv = exchange("/v1/billing/checkout/" + queueEntryId, HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "lineItems", List.of(Map.of("override", Map.of("reasonCode", "other"), "chargeCode", "QAFREE", "chargeName", "Consultation",
                        "category", "Consultation", "quantity", 1, "unitPrice", amount, "taxRatePercent", 0)))), Map.class);
        assertThat(inv.getStatusCode().is2xxSuccessful()).as("%s", inv.getBody()).isTrue();
        String id = (String) inv.getBody().get("id");
        for (int i = 0; i < payments.length; i += 2) {
            ResponseEntity<Map> p = exchange("/v1/billing/invoices/" + id + "/payments", HttpMethod.POST,
                    authedJsonBody(c.token(), Map.of("method", payments[i], "amount", payments[i + 1])), Map.class);
            assertThat(p.getStatusCode().is2xxSuccessful()).as("%s", p.getBody()).isTrue();
        }
        return id;
    }

    private UUID appointment(Clinic c, String patientId, String status, Instant start) {
        UUID id = UUID.randomUUID();
        SU.update("INSERT INTO appointments (id, tenant_id, patient_id, doctor_id, start_time, end_time, status) VALUES (?,?,?,?,?,?,?)",
                id, c.tenant().id(), UUID.fromString(patientId), c.owner().id(), Timestamp.from(start), Timestamp.from(start.plusSeconds(1)), status);
        return id;
    }

    private ResponseEntity<Map> desk(String token, String query) {
        return exchange("/v1/reports/desk-summary" + query, HttpMethod.GET, authed(token), Map.class);
    }

    private static double amountOf(List<Map<String, Object>> modes, String method) {
        return ((Number) modes.stream().filter(m -> method.equals(m.get("method"))).findFirst().orElseThrow().get("amount")).doubleValue();
    }

    // A-API-01: AC1 figures with split payments and a refund, the same figures as the day close (AC3's expected totals)
    @Test
    void s17qa_A_API_01_figuresMatchTheDayCloseIncludingSplitPaymentsAndRefunds() {
        Clinic a = clinic(null, 0);
        String paidCash = visitAtCheckout(a, patient(a, "Qa Cashpaid"));
        String inv = bill(a, paidCash, 700, "cash", 700);
        String split = visitAtCheckout(a, patient(a, "Qa Splitpaid"));
        bill(a, split, 500, "card", 300, "upi", 200);
        // a cash refund of 100 handed back today (the refund approval flow writes exactly this row shape)
        UUID payment = SU.queryForObject("SELECT id FROM invoice_payments WHERE invoice_id = ?::uuid", UUID.class, inv);
        SU.update("INSERT INTO invoice_payments (tenant_id, invoice_id, method, amount, recorded_by, kind, refund_of_payment_id, currency) VALUES (?,?::uuid,'cash',-100,?,'refund',?,'INR')",
                a.tenant().id(), inv, a.owner().id(), payment);
        String unbilled = visitAtCheckout(a, patient(a, "Qa Unbilled"));
        Instant dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        UUID noShow = appointment(a, patient(a, "Qa Noshow"), "scheduled", dayStart.plusSeconds(1));
        UUID later = appointment(a, patient(a, "Qa Laterbooking"), "scheduled", Instant.now().plusSeconds(90));
        appointment(a, patient(a, "Qa Cancelled"), "cancelled", dayStart.plusSeconds(3));

        String reception = receptionist(a);
        ResponseEntity<Map> r = desk(reception, "");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> s = r.getBody();
        assertThat((Map<String, Object>) s.get("visits")).containsEntry("total", 3);
        List<Map<String, Object>> modes = (List<Map<String, Object>>) s.get("collectionsByMode");
        assertThat(modes).extracting(m -> m.get("method")).containsExactly("cash", "card", "upi", "other");
        assertThat(amountOf(modes, "cash")).isEqualTo(600.0);
        assertThat(amountOf(modes, "card")).isEqualTo(300.0);
        assertThat(amountOf(modes, "upi")).isEqualTo(200.0);
        assertThat(amountOf(modes, "other")).isEqualTo(0.0);
        assertThat((List<Map<String, Object>>) s.get("unbilled")).singleElement().satisfies(u -> assertThat(u)
                .containsEntry("queueEntryId", unbilled).containsEntry("patientName", "Qa Unbilled").containsEntry("doctorName", "Test Staff"));
        List<Map<String, Object>> ns = (List<Map<String, Object>>) s.get("noShows");
        assertThat(ns).extracting(n -> n.get("appointmentId")).containsExactly(noShow.toString()).doesNotContain(later.toString());

        Map<String, Object> close = exchange("/v1/billing/day-close", HttpMethod.GET, authed(a.token()), Map.class).getBody();
        assertThat(s.get("collectionsByMode")).isEqualTo(close.get("methods"));
        assertThat(s.get("unbilled")).isEqualTo(close.get("unbilled"));
        assertThat(s.get("date")).isEqualTo(close.get("date"));
        assertThat(s.get("currency")).isEqualTo(close.get("currency"));
    }

    // A-API-02: AC4 no other clinic's data, today and for an explicit date
    @Test
    void s17qa_A_API_02_noOtherBranchDataForTodayOrAnExplicitDate() {
        Clinic a = clinic(null, 0);
        Clinic b = clinic(null, 0);
        visitAtCheckout(a, patient(a, "Qa Ownbranch"));
        String paidB = visitAtCheckout(b, patient(b, "Qa Otherbranchpaid"));
        bill(b, paidB, 98765, "card", 98765);
        String unbilledB = visitAtCheckout(b, patient(b, "Qa Otherbranchunbilled"));
        UUID noShowB = appointment(b, patient(b, "Qa Otherbranchnoshow"), "scheduled", LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(5));
        String reception = receptionist(a);
        String today = (String) desk(reception, "").getBody().get("date");
        for (String q : List.of("", "?date=" + today)) {
            ResponseEntity<String> raw = exchange("/v1/reports/desk-summary" + q, HttpMethod.GET, authed(reception), String.class);
            assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(raw.getBody()).doesNotContain("Otherbranch", paidB, unbilledB, noShowB.toString(), b.tenant().id().toString(), "98765");
            assertThat(raw.getBody()).contains("Qa Ownbranch");
        }
        Map<String, Object> sb = desk(b.token(), "").getBody();
        assertThat(amountOf((List<Map<String, Object>>) sb.get("collectionsByMode"), "card")).isEqualTo(98765.0);
        assertThat(sb.toString()).doesNotContain("Qa Ownbranch");
    }

    // A-API-03: permission gate
    @Test
    void s17qa_A_API_03_needsQueueViewAndAToken() {
        Clinic c = clinic(null, 0);
        UUID billingOnly = seedRole(c.tenant().id(), "Cashier" + uid(), false, new ModuleGrant("billing", true, true, true, true, true, true, true));
        String cashier = loginAndGetAccessToken(seedStaff(c.tenant(), billingOnly, "qa44b" + uid() + "@c.com", phone(), false));
        assertThat(desk(cashier, "").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/reports/desk-summary", HttpMethod.GET, HttpEntity.EMPTY, Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(desk(receptionist(c), "").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // A-API-04: empty day
    @Test
    void s17qa_A_API_04_emptyDayHasZeroFiguresAndAllFourModes() {
        Clinic c = clinic(null, 0);
        Map<String, Object> s = desk(receptionist(c), "").getBody();
        assertThat(s).containsEntry("today", true).containsEntry("currency", "INR");
        assertThat((Map<String, Object>) s.get("visits")).containsEntry("total", 0).containsEntry("completed", 0).containsEntry("inFlow", 0);
        List<Map<String, Object>> modes = (List<Map<String, Object>>) s.get("collectionsByMode");
        assertThat(modes).hasSize(4).allSatisfy(m -> {
            assertThat(((Number) m.get("amount")).doubleValue()).isZero();
            assertThat(m.get("transactions")).isEqualTo(0);
        });
        assertThat((List<?>) s.get("unbilled")).isEmpty();
        assertThat((List<?>) s.get("noShows")).isEmpty();
    }

    // A-API-05: the 04:00 clinic-day start in Asia/Kolkata splits payments and no-shows exactly like the day close
    @Test
    void s17qa_A_API_05_clinicDayStartAt0400SplitsPaymentsAndNoShowsLikeTheDayClose() {
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        Clinic c = clinic("Asia/Kolkata", 240);
        LocalDate clinicToday = ZonedDateTime.now(ist).minusMinutes(240).toLocalDate();
        LocalDate d = clinicToday.minusDays(2);
        LocalDate d1 = clinicToday.minusDays(1);
        Instant boundary = d1.atTime(4, 0).atZone(ist).toInstant(); // first instant of clinic day d1

        String v1 = visitAtCheckout(c, patient(c, "Qa Beforefour"));
        String inv1 = bill(c, v1, 111, "cash", 111);
        String v2 = visitAtCheckout(c, patient(c, "Qa Atfour"));
        String inv2 = bill(c, v2, 222, "card", 222);
        SU.update("UPDATE invoice_payments SET recorded_at = ? WHERE invoice_id = ?::uuid", Timestamp.from(boundary.minusSeconds(1)), inv1);
        SU.update("UPDATE invoice_payments SET recorded_at = ? WHERE invoice_id = ?::uuid", Timestamp.from(boundary), inv2);
        UUID late = appointment(c, patient(c, "Qa Nightbooking"), "scheduled", boundary.minusSeconds(60)); // 03:59 local: still day d

        String reception = receptionist(c);
        Map<String, Object> onD = desk(reception, "?date=" + d).getBody();
        Map<String, Object> onD1 = desk(reception, "?date=" + d1).getBody();
        List<Map<String, Object>> mD = (List<Map<String, Object>>) onD.get("collectionsByMode");
        List<Map<String, Object>> mD1 = (List<Map<String, Object>>) onD1.get("collectionsByMode");
        assertThat(amountOf(mD, "cash")).as("03:59:59 payment belongs to the previous clinic day").isEqualTo(111.0);
        assertThat(amountOf(mD, "card")).isEqualTo(0.0);
        assertThat(amountOf(mD1, "card")).as("04:00:00 payment opens the next clinic day").isEqualTo(222.0);
        assertThat(amountOf(mD1, "cash")).isEqualTo(0.0);
        assertThat((List<Map<String, Object>>) onD.get("noShows")).extracting(n -> n.get("appointmentId")).containsExactly(late.toString());
        assertThat((List<?>) onD1.get("noShows")).isEmpty();
        assertThat(onD).containsEntry("today", false).containsEntry("date", d.toString());
        for (LocalDate day : List.of(d, d1)) {
            Map<String, Object> close = exchange("/v1/billing/day-close?date=" + day, HttpMethod.GET, authed(c.token()), Map.class).getBody();
            assertThat(desk(reception, "?date=" + day).getBody().get("collectionsByMode")).as("day %s", day).isEqualTo(close.get("methods"));
        }
    }

    // A-API-06: a bad date is a 400 problem, never a 500
    @Test
    void s17qa_A_API_06_badDateIs400() {
        Clinic c = clinic(null, 0);
        String reception = receptionist(c);
        for (String bad : List.of("not-a-date", "2026-13-40", "28-10-2026")) {
            assertThat(desk(reception, "?date=" + bad).getStatusCode()).as(bad).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // A-API-07: AC2 an unbilled row's queueEntryId opens that visit's checkout
    @Test
    void s17qa_A_API_07_unbilledQueueEntryIdOpensThatVisitsCheckout() {
        Clinic c = clinic(null, 0);
        visitAtCheckout(c, patient(c, "Qa Firstunbilled"));
        String second = visitAtCheckout(c, patient(c, "Qa Secondunbilled"));
        visitAtCheckout(c, patient(c, "Qa Thirdunbilled"));
        List<Map<String, Object>> unbilled = (List<Map<String, Object>>) desk(receptionist(c), "").getBody().get("unbilled");
        assertThat(unbilled).hasSize(3);
        Map<String, Object> row = unbilled.stream().filter(u -> second.equals(u.get("queueEntryId"))).findFirst().orElseThrow();
        ResponseEntity<Map> ctx = exchange("/v1/billing/checkout/" + row.get("queueEntryId"), HttpMethod.GET, authed(c.token()), Map.class);
        assertThat(ctx.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ctx.getBody()).containsEntry("queueEntryId", second).containsEntry("patientName", "Qa Secondunbilled");
    }
}
