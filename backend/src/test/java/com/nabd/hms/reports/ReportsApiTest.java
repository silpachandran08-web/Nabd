package com.nabd.hms.reports;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ReportsApiTest extends ApiTestBase {

    // A distinct dob per call sidesteps NB-060's fuzzy duplicate-candidate match (dob + name
    // similarity) — short similarly-named test patients within one tenant would otherwise collide.
    private static final AtomicInteger DOB_YEAR = new AtomicInteger(1960);

    private String registerPatient(String token, String name, String phone) {
        String dob = (DOB_YEAR.getAndIncrement()) + "-01-01";
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", dob, "gender", "male")), Map.class);
        return (String) resp.getBody().get("id");
    }

    private String checkIn(String token, String patientId, UUID doctorId, String source) {
        Map<String, Object> body = source == null
                ? Map.of("patientId", patientId, "doctorId", doctorId.toString())
                : Map.of("patientId", patientId, "doctorId", doctorId.toString(), "source", source);
        ResponseEntity<Map> resp = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, body), Map.class);
        return (String) resp.getBody().get("id");
    }

    private void moveTo(String token, String queueEntryId, String... statuses) {
        for (String s : statuses) {
            exchange("/v1/queue/" + queueEntryId + "/status", HttpMethod.PATCH,
                    authedJsonBody(token, Map.of("status", s)), Map.class);
        }
    }

    private String checkoutAndPay(String token, String queueEntryId, double amount) {
        ResponseEntity<Map> invoice = exchange("/v1/billing/checkout/" + queueEntryId, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("override", Map.of("reasonCode", "other"), "chargeCode", "X", "chargeName", "X", "category", "Service",
                        "quantity", 1, "unitPrice", amount, "taxRatePercent", 0)))), Map.class);
        String invoiceId = (String) invoice.getBody().get("id");
        exchange("/v1/billing/invoices/" + invoiceId + "/payments", HttpMethod.POST,
                authedJsonBody(token, Map.of("method", "cash", "amount", amount)), Map.class);
        return invoiceId;
    }

    @Test
    @SuppressWarnings("unchecked")
    void moneyReportCoversThePeriodAndOtherTabsFollowIt() {
        SeededTenant tenant = seedTenant();
        SeededStaff owner = seedStaff(tenant, seedFullAccessRole(tenant.id()), "mo1@a.com", "+919800068001", false);
        String token = loginAndGetAccessToken(owner);
        String paid = checkIn(token, registerPatient(token, "Mo Paid", "+919999968001"), owner.id(), null);
        moveTo(token, paid, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        checkoutAndPay(token, paid, 500);                                       // line "X", 0% tax, cash
        checkIn(token, registerPatient(token, "Mo Waiting", "+919999968002"), owner.id(), null); // still waiting
        String noShow = registerPatient(token, "Mo NoShow", "+919999968003");
        inTenantTx(tenant.id(), () -> jdbc.update(
                "INSERT INTO appointments (tenant_id, patient_id, doctor_id, start_time, end_time) VALUES (?,?::uuid,?, now() - interval '1 minute', now() + interval '14 minutes')",
                tenant.id(), noShow, owner.id()));                              // booked, time passed, never checked in

        Map<String, Object> m = exchange("/v1/reports/money", HttpMethod.GET, authed(token), Map.class).getBody();
        assertThat(m).containsEntry("includesToday", true).containsEntry("currency", "INR").containsEntry("taxLabel", "GST")
                .containsEntry("billsRaised", 1).containsEntry("bookings", 1).containsEntry("noShows", 1).containsEntry("waitingNow", 1);
        assertThat(((Number) m.get("collected")).doubleValue()).isEqualTo(500.0);
        assertThat((List<Map<String, Object>>) m.get("tender")).singleElement().satisfies(x -> assertThat(x).containsEntry("method", "cash").containsEntry("count", 1));
        assertThat((List<Map<String, Object>>) m.get("topServices")).singleElement().satisfies(x -> {
            assertThat(x).containsEntry("name", "X");
            assertThat(((Number) x.get("revenue")).doubleValue()).isEqualTo(500.0);
        });

        String yesterday = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1).toString(); // test tenants are UTC
        Map<String, Object> y = exchange("/v1/reports/money?from=" + yesterday + "&to=" + yesterday, HttpMethod.GET, authed(token), Map.class).getBody();
        assertThat(y).containsEntry("includesToday", false).containsEntry("billsRaised", 0).containsEntry("waitingNow", null);
        assertThat(exchange("/v1/reports/sources?from=" + yesterday + "&to=" + yesterday, HttpMethod.GET, authed(token), List.class).getBody()).isEmpty();
        assertThat(exchange("/v1/reports/sources", HttpMethod.GET, authed(token), List.class).getBody()).isNotEmpty(); // default: last 30 days

        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        assertThat(exchange("/v1/reports/money?from=" + today + "&to=" + yesterday, HttpMethod.GET, authed(token), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private void pay(String token, String invoiceId, String method, double amount) {
        exchange("/v1/billing/invoices/" + invoiceId + "/payments", HttpMethod.POST,
                authedJsonBody(token, Map.of("method", method, "amount", amount)), Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void moneyReportCountsPaymentsPerTenderAndDoesNotLeakAcrossTenants() { // OWN-023
        SeededTenant tenant = seedTenant();
        SeededTenant other = seedTenant();
        SeededStaff owner = seedStaff(tenant, seedFullAccessRole(tenant.id()), "mt1@a.com", "+919800068101", false);
        SeededStaff otherOwner = seedStaff(other, seedFullAccessRole(other.id()), "mt2@b.com", "+919800068102", false);
        String token = loginAndGetAccessToken(owner);
        String otherToken = loginAndGetAccessToken(otherOwner);

        // visit 1: one 500 bill split 200 cash + 300 card; visit 2: 100 cash in full
        String e1 = checkIn(token, registerPatient(token, "Mt One", "+919999968101"), owner.id(), null);
        moveTo(token, e1, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        ResponseEntity<Map> inv = exchange("/v1/billing/checkout/" + e1, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("override", Map.of("reasonCode", "other"), "chargeCode", "X", "chargeName", "X", "category", "Service",
                        "quantity", 1, "unitPrice", 500, "taxRatePercent", 0)))), Map.class);
        String invoiceId = (String) inv.getBody().get("id");
        pay(token, invoiceId, "cash", 200);
        pay(token, invoiceId, "card", 300);
        String e2 = checkIn(token, registerPatient(token, "Mt Two", "+919999968102"), owner.id(), null);
        moveTo(token, e2, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        checkoutAndPay(token, e2, 100);

        Map<String, Object> m = exchange("/v1/reports/money", HttpMethod.GET, authed(token), Map.class).getBody();
        List<Map<String, Object>> tender = (List<Map<String, Object>>) m.get("tender");
        assertThat(tender).hasSize(2);
        Map<String, Object> cash = tender.stream().filter(t -> "cash".equals(t.get("method"))).findFirst().orElseThrow();
        Map<String, Object> card = tender.stream().filter(t -> "card".equals(t.get("method"))).findFirst().orElseThrow();
        assertThat(cash).containsEntry("count", 2);
        assertThat(((Number) cash.get("amount")).doubleValue()).isEqualTo(300.0);
        assertThat(card).containsEntry("count", 1);
        assertThat(((Number) card.get("amount")).doubleValue()).isEqualTo(300.0);
        assertThat(((Number) m.get("collected")).doubleValue()).isEqualTo(600.0);

        // the other clinic's report sees none of these payments
        Map<String, Object> o = exchange("/v1/reports/money", HttpMethod.GET, authed(otherToken), Map.class).getBody();
        assertThat((List<?>) o.get("tender")).isEmpty();
        assertThat(((Number) o.get("collected")).doubleValue()).isEqualTo(0.0);
    }

    @Test
    void overviewSummarisesTheClinicsDay() {
        SeededTenant tenant = seedTenant();
        SeededStaff owner = seedStaff(tenant, seedFullAccessRole(tenant.id()), "ov1@a.com", "+919800069001", false);
        String token = loginAndGetAccessToken(owner); // a live session opened today

        // A: checked out, 200 of 500 paid by UPI (partial invoice)
        String a = checkIn(token, registerPatient(token, "Ov A", "+919999969001"), owner.id(), null);
        moveTo(token, a, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        ResponseEntity<Map> inv = exchange("/v1/billing/checkout/" + a, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("override", Map.of("reasonCode", "other"), "chargeCode", "X", "chargeName", "X", "category", "Service",
                        "quantity", 1, "unitPrice", 500, "taxRatePercent", 0)))), Map.class);
        exchange("/v1/billing/invoices/" + inv.getBody().get("id") + "/payments", HttpMethod.POST,
                authedJsonBody(token, Map.of("method", "upi", "amount", 200)), Map.class);
        // B: waiting at checkout
        String b = checkIn(token, registerPatient(token, "Ov B", "+919999969002"), owner.id(), null);
        moveTo(token, b, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        // C: with the doctor right now
        String c = checkIn(token, registerPatient(token, "Ov C", "+919999969003"), owner.id(), null);
        moveTo(token, c, "waiting", "vitals_pending", "vitals_done", "in_consult");

        ResponseEntity<Map> resp = exchange("/v1/reports/overview", HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = resp.getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> visits = (Map<String, Object>) body.get("visits");
        assertThat(visits).containsEntry("total", 3).containsEntry("completed", 1).containsEntry("inFlow", 2); // A done; B, C in flow
        assertThat(((Map<?, ?>) body.get("checkout")).get("pending")).isEqualTo(1);
        Map<?, ?> collections = (Map<?, ?>) body.get("collections");
        assertThat(((Number) collections.get("collected")).doubleValue()).isEqualTo(200.0);
        assertThat(collections.get("invoices")).isEqualTo(1);
        assertThat(collections.get("pendingInvoices")).isEqualTo(1);
        assertThat(((Number) ((Map<?, ?>) body.get("checkout")).get("outstanding")).doubleValue()).isEqualTo(300.0);
        assertThat((List<?>) body.get("paymentSplit")).singleElement().satisfies(p -> {
            assertThat(((Map<?, ?>) p).get("method")).isEqualTo("upi");
            assertThat(((Number) ((Map<?, ?>) p).get("amount")).doubleValue()).isEqualTo(200.0);
        });
        // B is at checkout with no bill (blocks the close); A's partial balance doesn't block it
        assertThat(((Map<?, ?>) body.get("dayClose"))).isEqualTo(Map.of("unbilledConsultations", 1, "pendingCheckouts", 1, "closed", false));
        assertThat((List<?>) body.get("activeStaff")).singleElement().satisfies(s -> {
            assertThat(((Map<?, ?>) s).get("name")).isEqualTo("Test Staff");
            assertThat(((Map<?, ?>) s).get("inConsult")).isEqualTo(true);
        });
        assertThat(((Map<?, ?>) body.get("staff")).get("active")).isEqualTo(1);
    }

    @Test
    void overviewNeedsReportsView() {
        SeededTenant tenant = seedTenant();
        UUID nurseRole = seedRole(tenant.id(), "Nurse", false, fullGrant("nursing"));
        String token = loginAndGetAccessToken(seedStaff(tenant, nurseRole, "ovn@a.com", "+919800069009", false));
        assertThat(exchange("/v1/reports/overview", HttpMethod.GET, authed(token), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void dailyMoneyReflectsTodaysInvoicingAndCollection() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner10@a.com", "+919800060001", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "R1", "+919999970101");
        String queueEntryId = checkIn(token, patientId, staff.id(), null);
        moveTo(token, queueEntryId, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        checkoutAndPay(token, queueEntryId, 500);

        ResponseEntity<Map> resp = exchange("/v1/reports/daily-money", HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) resp.getBody().get("billedToday")).doubleValue()).isEqualTo(500.0);
        assertThat(((Number) resp.getBody().get("collectedToday")).doubleValue()).isEqualTo(500.0);
        assertThat(((Number) resp.getBody().get("invoiceCountToday")).intValue()).isEqualTo(1);
        assertThat(((Number) resp.getBody().get("paymentCountToday")).intValue()).isEqualTo(1);
    }

    @Test
    void sourceBreakdownReflectsCheckInSourceTags() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner11@a.com", "+919800060002", false);
        String token = loginAndGetAccessToken(staff);
        checkIn(token, registerPatient(token, "R2a", "+919999970102"), staff.id(), "referral");
        checkIn(token, registerPatient(token, "R2b", "+919999970103"), staff.id(), "referral");
        checkIn(token, registerPatient(token, "R2c", "+919999970104"), staff.id(), null); // defaults to walk_in

        ResponseEntity<List> resp = exchange("/v1/reports/sources", HttpMethod.GET, authed(token), List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map> rows = resp.getBody();
        Map referralRow = rows.stream().filter(m -> "referral".equals(m.get("source"))).findFirst().orElseThrow();
        assertThat(((Number) referralRow.get("visitCount")).longValue()).isEqualTo(2L);
        assertThat(((Number) referralRow.get("revenue")).doubleValue()).isZero();
        assertThat(((Number) referralRow.get("untaggedCount")).longValue()).isZero(); // source is NOT NULL, defaults to walk_in
    }

    @Test
    void sourceBreakdownCarriesRevenueOfTheVisitsInEachSource() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "owner31@a.com", "+919800060031", false);
        String token = loginAndGetAccessToken(staff);
        String e = checkIn(token, registerPatient(token, "S1a", "+919999970131"), staff.id(), "online");
        moveTo(token, e, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        checkoutAndPay(token, e, 450);
        checkIn(token, registerPatient(token, "S1b", "+919999970132"), staff.id(), "online"); // unbilled

        List<Map> rows = exchange("/v1/reports/sources", HttpMethod.GET, authed(token), List.class).getBody();

        Map online = rows.stream().filter(m -> "online".equals(m.get("source"))).findFirst().orElseThrow();
        assertThat(((Number) online.get("visitCount")).longValue()).isEqualTo(2L);
        assertThat(((Number) online.get("revenue")).doubleValue()).isEqualTo(450.0);
    }

    @Test
    void staffPerformanceCountsHandledDiscountsAndOverridesPerStaffMember() {
        SeededTenant tenant = seedTenant();
        SeededStaff owner = seedStaff(tenant, seedFullAccessRole(tenant.id()), "owner32@a.com", "+919800060032", false);
        String token = loginAndGetAccessToken(owner);
        String e = checkIn(token, registerPatient(token, "S2a", "+919999970133"), owner.id(), null);
        moveTo(token, e, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        ResponseEntity<Map> invoice = exchange("/v1/billing/checkout/" + e, HttpMethod.POST, authedJsonBody(token, Map.of(
                "discount", 10, "lineItems", List.of(Map.of("override", Map.of("reasonCode", "other"), "chargeCode", "X", "chargeName", "X", "category", "Service",
                        "quantity", 1, "unitPrice", 200, "taxRatePercent", 0)))), Map.class);
        exchange("/v1/billing/invoices/" + invoice.getBody().get("id") + "/payments", HttpMethod.POST,
                authedJsonBody(token, Map.of("method", "cash", "amount", 190)), Map.class);
        moveTo(token, e, "completed");
        inTenantTx(tenant.id(), () -> jdbc.update(
                "INSERT INTO audit_log (tenant_id, actor_type, actor_id, actor_name, actor_role, action, entity_type) " +
                        "VALUES (?, 'staff', ?, 'o', 'Owner', 'prescription.allergy_override', 'prescription')", tenant.id(), owner.id()));

        Map body = exchange("/v1/reports/staff-performance", HttpMethod.GET, authed(token), Map.class).getBody();

        Map row = (Map) ((List<?>) body.get("rows")).get(0);
        assertThat(((Number) row.get("handledCount")).longValue()).isEqualTo(1L);   // same visit as doctor and biller counts once
        assertThat(((Number) row.get("discountCount")).longValue()).isEqualTo(1L);
        assertThat(((Number) row.get("overrideCount")).longValue()).isEqualTo(1L);
        assertThat(((Number) row.get("collected")).doubleValue()).isEqualTo(190.0);
    }

    @Test
    void staffPerformanceScopesToOwnRowForAnOwnPatientsOnlyStaffMember() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff owner = seedStaff(tenant, roleId, "owner12@a.com", "+919800060003", false);
        SeededStaff doc = seedStaff(tenant, roleId, "doc12@a.com", "+919800060004", false);
        String ownerToken = loginAndGetAccessToken(owner);
        exchange("/v1/staff/" + doc.id(), HttpMethod.PATCH, authedJsonBody(ownerToken, Map.of(
                "roleId", roleId.toString(), "scope", "own_patients_only")), Map.class);

        String p1 = registerPatient(ownerToken, "R3a", "+919999970105");
        String e1 = checkIn(ownerToken, p1, owner.id(), null);
        moveTo(ownerToken, e1, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        checkoutAndPay(ownerToken, e1, 300);

        String docToken = loginAndGetAccessToken(doc);
        String p2 = registerPatient(docToken, "R3b", "+919999970106");
        String e2 = checkIn(docToken, p2, doc.id(), null);
        moveTo(docToken, e2, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        checkoutAndPay(docToken, e2, 700);

        ResponseEntity<Map> scoped = exchange("/v1/reports/staff-performance", HttpMethod.GET, authed(docToken), Map.class);
        List<?> scopedRows = (List<?>) scoped.getBody().get("rows");
        assertThat(scopedRows).hasSize(1);
        assertThat(((Map<?, ?>) scopedRows.get(0)).get("staffId")).isEqualTo(doc.id().toString());
        assertThat(scoped.getBody().get("scopeNote")).asString().contains("only your own row");

        ResponseEntity<Map> unscoped = exchange("/v1/reports/staff-performance", HttpMethod.GET, authed(ownerToken), Map.class);
        assertThat((List<?>) unscoped.getBody().get("rows")).hasSize(2);
        assertThat(unscoped.getBody().get("scopeNote")).asString().contains("every staff member");
    }

    @Test
    void retentionCountsRepeatVisitsInAggregateOnly() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner13@a.com", "+919800060005", false);
        String token = loginAndGetAccessToken(staff);
        String repeatPatient = registerPatient(token, "R4a", "+919999970107");
        for (int i = 0; i < 2; i++) {
            String e = checkIn(token, repeatPatient, staff.id(), null);
            moveTo(token, e, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
            checkoutAndPay(token, e, 100);
        }
        String oneVisitPatient = registerPatient(token, "R4b", "+919999970108");
        String e3 = checkIn(token, oneVisitPatient, staff.id(), null);
        moveTo(token, e3, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        checkoutAndPay(token, e3, 100);

        ResponseEntity<Map> resp = exchange("/v1/reports/retention", HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getBody().get("repeatPatients")).isEqualTo(1);
        assertThat(resp.getBody()).doesNotContainKey("patients"); // NB-234: aggregate only, no patient list
    }

    @Test
    void noShowRiskFlagsAPatientWithEnoughPriorNoShowsAndStatesTheRule() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner14@a.com", "+919800060006", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "R5", "+919999970109");
        for (int i = 0; i < 2; i++) {
            String e = checkIn(token, patientId, staff.id(), null);
            moveTo(token, e, "no_show");
        }
        String todayEntry = checkIn(token, patientId, staff.id(), null);

        ResponseEntity<Map> resp = exchange("/v1/reports/no-show-risk", HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getBody().get("rule")).asString().contains("2+ no-shows");
        List<Map> entries = (List<Map>) resp.getBody().get("entries");
        Map flagged = entries.stream().filter(m -> patientId.equals(m.get("patientId"))).findFirst().orElseThrow();
        assertThat(((Number) flagged.get("priorNoShowCount")).longValue()).isEqualTo(2L);
    }

    @Test
    void exportWritesAnAuditRowWithTheRowCount() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner15@a.com", "+919800060007", false);
        String token = loginAndGetAccessToken(staff);
        checkIn(token, registerPatient(token, "R6", "+919999970110"), staff.id(), "online");

        ResponseEntity<String> resp = exchange("/v1/reports/export?reportType=sources", HttpMethod.GET, authed(token), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("source,visitCount").contains("online");

        Map<String, Object> audited = inTenantTx(tenant.id(), () -> jdbc.queryForMap(
                "SELECT action, after FROM audit_log WHERE tenant_id = ? AND action = 'report.export' " +
                        "ORDER BY created_at DESC LIMIT 1",
                tenant.id()));
        assertThat(audited.get("after").toString()).contains("sources").contains("rowCount");
    }

    private void seedCharge(SeededTenant tenant, String code, String name, double amount) {
        inTenantTx(tenant.id(), () -> jdbc.update(
                "INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, tax_rate_percent) VALUES (?,?,?,?,?,?)",
                tenant.id(), code, name, "Procedure", amount, 0.0));
    }

    private String orderAndCompleteProcedure(String token, String queueEntryId, String chargeCode) {
        ResponseEntity<Map> order = exchange("/v1/nursing/procedure-orders", HttpMethod.POST, authedJsonBody(token, Map.of(
                "queueEntryId", queueEntryId, "chargeCode", chargeCode)), Map.class);
        String procedureId = (String) order.getBody().get("id");
        exchange("/v1/nursing/procedure-orders/" + procedureId + "/consent", HttpMethod.POST,
                authedJsonBody(token, Map.of("signedName", "Consent")), Map.class);
        exchange("/v1/nursing/procedure-orders/" + procedureId + "/status", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("status", "completed")), Map.class);
        return procedureId;
    }

    @Test
    void billingLeakageFlagsACompletedProcedureNeverAddedToTheInvoiceButNotAProperlyBilledOne() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner16@a.com", "+919800060009", false);
        String token = loginAndGetAccessToken(staff);
        seedCharge(tenant, "LEAK-1", "Leaked procedure", 400.00);
        seedCharge(tenant, "OK-1", "Billed procedure", 250.00);

        String leakedPatient = registerPatient(token, "R7a", "+919999970111");
        String leakedEntry = checkIn(token, leakedPatient, staff.id(), null);
        orderAndCompleteProcedure(token, leakedEntry, "LEAK-1");
        moveTo(token, leakedEntry, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        // checkout without adding LEAK-1's charge to the invoice — this is the leak
        exchange("/v1/billing/checkout/" + leakedEntry, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("override", Map.of("reasonCode", "other"), "chargeCode", "X", "chargeName", "X", "category", "Service",
                        "quantity", 1, "unitPrice", 100, "taxRatePercent", 0)))), Map.class);

        String billedPatient = registerPatient(token, "R7b", "+919999970112");
        String billedEntry = checkIn(token, billedPatient, staff.id(), null);
        orderAndCompleteProcedure(token, billedEntry, "OK-1");
        moveTo(token, billedEntry, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        exchange("/v1/billing/checkout/" + billedEntry, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("override", Map.of("reasonCode", "other"), "chargeCode", "OK-1", "chargeName", "Billed procedure", "category", "Procedure",
                        "quantity", 1, "unitPrice", 250, "taxRatePercent", 0)))), Map.class);

        ResponseEntity<Map> resp = exchange("/v1/reports/billing-leakage?thresholdAmount=0", HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map> entries = (List<Map>) resp.getBody().get("entries");
        assertThat(entries).extracting(e -> e.get("chargeCode")).containsExactly("LEAK-1");
    }

    @Test
    void billingLeakageRespectsTheAmountThreshold() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner17@a.com", "+919800060010", false);
        String token = loginAndGetAccessToken(staff);
        seedCharge(tenant, "LEAK-2", "Small leak", 50.00);
        String patientId = registerPatient(token, "R8", "+919999970113");
        String entry = checkIn(token, patientId, staff.id(), null);
        orderAndCompleteProcedure(token, entry, "LEAK-2");
        moveTo(token, entry, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        exchange("/v1/billing/checkout/" + entry, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("override", Map.of("reasonCode", "other"), "chargeCode", "X", "chargeName", "X", "category", "Service",
                        "quantity", 1, "unitPrice", 100, "taxRatePercent", 0)))), Map.class);

        ResponseEntity<Map> resp = exchange("/v1/reports/billing-leakage?thresholdAmount=100", HttpMethod.GET, authed(token), Map.class);

        assertThat((List<?>) resp.getBody().get("entries")).isEmpty();
    }

    @Test
    void doctorPunctualityAggregatesDelayCountAverageMinutesAndSameDayRepeatsAndStatesAccessNote() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner18@a.com", "+919800060011", false);
        String token = loginAndGetAccessToken(staff);

        exchange("/v1/doctors/" + staff.id() + "/delay", HttpMethod.POST,
                authedJsonBody(token, Map.of("delayMinutes", 10)), Map.class);
        exchange("/v1/doctors/" + staff.id() + "/delay/clear", HttpMethod.POST, authed(token), Map.class);
        exchange("/v1/doctors/" + staff.id() + "/delay", HttpMethod.POST,
                authedJsonBody(token, Map.of("delayMinutes", 20)), Map.class);

        ResponseEntity<Map> resp = exchange("/v1/reports/doctor-punctuality", HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("accessNote")).asString().contains("Owner-only");
        List<Map> entries = (List<Map>) resp.getBody().get("entries");
        Map entry = entries.stream().filter(e -> staff.id().toString().equals(e.get("doctorId"))).findFirst().orElseThrow();
        assertThat(((Number) entry.get("delayCount")).longValue()).isEqualTo(2L);
        assertThat(((Number) entry.get("avgDelayMinutes")).doubleValue()).isEqualTo(15.0);
        assertThat(((Number) entry.get("sameDayRepeatDays")).longValue()).isEqualTo(1L);
    }

    @Test
    void roleWithoutReportsGrantIsForbidden() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "QueueOnly", false, fullGrant("queue"), fullGrant("patients"));
        SeededStaff staff = seedStaff(tenant, roleId, "norole2@a.com", "+919800060008", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/reports/daily-money", HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** OWN-026: sales per clinic day, redemption over instances sold in the period, expired-with-value. */
    @Test
    @SuppressWarnings("unchecked")
    void packagesReportShowsSalesRedemptionAndExpiredValue() {
        SeededTenant tenant = seedTenant();
        String token = loginAndGetAccessToken(seedStaff(tenant, seedFullAccessRole(tenant.id()), "pk1@a.com", "+919800070001", false));
        ResponseEntity<Map> pkg = exchange("/v1/packages", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Glow Combo", "packageType", "combination", "speciality", "Dermatology", "description", "d",
                "price", 2400.00, "taxInclusive", false, "validityDays", 90, "validityStarts", "purchase_date", "graceDays", 7,
                "items", List.of(
                        Map.of("itemType", "service_session", "name", "Facial", "quantity", 2, "unitListPrice", 1000.00, "taxRatePercent", 18.0),
                        Map.of("itemType", "take_home_product", "name", "Cream", "quantity", 1, "unitListPrice", 1000.00, "taxRatePercent", 12.0)))),
                Map.class);
        String packageId = (String) pkg.getBody().get("id");
        exchange("/v1/packages/" + packageId + "/activate", HttpMethod.POST, authed(token), Map.class);
        Map<String, Object> a = sellPackage(token, registerPatient(token, "Lakshmi Iyer", "+919800070011"), packageId);
        Map<String, Object> b = sellPackage(token, registerPatient(token, "Vikram Shetty", "+919800070012"), packageId);
        String facial = ((List<Map<String, Object>>) a.get("items")).stream().filter(i -> "Facial".equals(i.get("name")))
                .findFirst().orElseThrow().get("id").toString();
        assertThat(exchange("/v1/packages/instances/items/" + facial + "/redeem", HttpMethod.POST, authed(token), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        LocalDate today = LocalDate.parse((String) exchange("/v1/reports/packages", HttpMethod.GET, authed(token), Map.class).getBody().get("to"));
        UUID bId = UUID.fromString((String) b.get("id"));
        inTenantTx(tenant.id(), () -> { // B's validity ended 30 days ago; 7 grace days are long gone
            jdbc.update("UPDATE package_instances SET validity_end = ? WHERE id = ?", java.sql.Date.valueOf(today.minusDays(30)), bId);
        });
        double saleTotals = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT SUM(i.total) FROM invoices i JOIN package_instances pi ON pi.invoice_id = i.id WHERE pi.tenant_id = ?",
                java.math.BigDecimal.class, tenant.id())).doubleValue();

        ResponseEntity<Map> resp = exchange("/v1/reports/packages?from=" + today.minusDays(40) + "&to=" + today,
                HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> sales = (List<Map<String, Object>>) resp.getBody().get("salesByDay");
        assertThat(sales).hasSize(41);
        assertThat(sales.get(0)).containsEntry("date", today.minusDays(40).toString()).containsEntry("count", 0);
        Map<String, Object> last = sales.get(40);
        assertThat(last).containsEntry("date", today.toString()).containsEntry("count", 2);
        assertThat(((Number) last.get("amount")).doubleValue()).isEqualTo(saleTotals).isGreaterThan(0);
        assertThat((Map<String, Object>) resp.getBody().get("redemption"))
                .containsEntry("sessionsSold", 6).containsEntry("sessionsRedeemed", 1);
        List<Map<String, Object>> expired = (List<Map<String, Object>>) resp.getBody().get("expiredWithValue");
        assertThat(expired).singleElement().satisfies(e -> {
            assertThat(e).containsEntry("instanceId", bId.toString()).containsEntry("patientName", "Vikram Shetty")
                    .containsEntry("packageName", "Glow Combo").containsEntry("expiredOn", today.minusDays(30).toString());
            assertThat(((Number) e.get("remainingValue")).doubleValue()).isEqualTo(2400.0);
        });

        // a period that ends before today sees no sales and no redemption base
        Map<String, Object> earlier = exchange("/v1/reports/packages?from=" + today.minusDays(5) + "&to=" + today.minusDays(1),
                HttpMethod.GET, authed(token), Map.class).getBody();
        assertThat(((List<Map<String, Object>>) earlier.get("salesByDay"))).allSatisfy(d -> assertThat(d).containsEntry("count", 0));
        assertThat((Map<String, Object>) earlier.get("redemption")).containsEntry("sessionsSold", 0);
        assertThat((List<?>) earlier.get("expiredWithValue")).isEmpty();
    }

    private Map<String, Object> sellPackage(String token, String patientId, String packageId) {
        ResponseEntity<Map> resp = exchange("/v1/packages/sell", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "packageId", packageId, "paymentMethod", "upi")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }
}
