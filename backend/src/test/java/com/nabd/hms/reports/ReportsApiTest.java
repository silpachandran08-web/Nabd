package com.nabd.hms.reports;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
                "lineItems", List.of(Map.of("chargeCode", "X", "chargeName", "X", "category", "Service",
                        "quantity", 1, "unitPrice", amount, "taxRatePercent", 0)))), Map.class);
        String invoiceId = (String) invoice.getBody().get("id");
        exchange("/v1/billing/invoices/" + invoiceId + "/payments", HttpMethod.POST,
                authedJsonBody(token, Map.of("method", "cash", "amount", amount)), Map.class);
        return invoiceId;
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

        ResponseEntity<List> scoped = exchange("/v1/reports/staff-performance", HttpMethod.GET, authed(docToken), List.class);
        assertThat(scoped.getBody()).hasSize(1);
        assertThat(((Map<?, ?>) scoped.getBody().get(0)).get("staffId")).isEqualTo(doc.id().toString());

        ResponseEntity<List> unscoped = exchange("/v1/reports/staff-performance", HttpMethod.GET, authed(ownerToken), List.class);
        assertThat(unscoped.getBody()).hasSize(2);
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

    @Test
    void roleWithoutReportsGrantIsForbidden() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "QueueOnly", false, fullGrant("queue"), fullGrant("patients"));
        SeededStaff staff = seedStaff(tenant, roleId, "norole2@a.com", "+919800060008", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/reports/daily-money", HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
