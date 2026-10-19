package com.nabd.hms.concurrency;

import com.nabd.hms.common.AuditService;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ConcurrencyHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPS-041 concurrency suite v1: two staff acting on the same appointment, slot, invoice, refund or audit chain at once.
 * Tests tagged known-defect-* assert the target behaviour and stay red until the named integrity fix lands.
 */
class ConcurrencyApiTest extends ApiTestBase {

    @Autowired
    AuditService auditService;

    private record Ctx(SeededTenant tenant, UUID roleId, SeededStaff staff, String token) {
    }

    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger(10000000);

    private Ctx setup() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "conc" + UUID.randomUUID() + "@a.com", "+9195" + (10000000 + (int) (Math.random() * 89999999)), false);
        return new Ctx(tenant, roleId, staff, loginAndGetAccessToken(staff));
    }

    private String registerPatient(String token) {
        int i = SEQ.incrementAndGet();
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Conc" + i, "phone", "+9199" + i, "dob", LocalDate.of(1940, 1, 1).plusDays(i % 20000).toString(), "gender", "male")), Map.class);
        assertThat(resp.getStatusCode()).as("register patient: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) resp.getBody().get("id");
    }

    private void allDayWorkingHours(Ctx c) {
        for (int dow = 0; dow < 7; dow++) {
            exchange("/v1/doctors/" + c.staff().id() + "/working-hours", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                    "dayOfWeek", dow, "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
        }
    }

    /** A 15-minute-aligned start a couple of hours out, today (UTC), with room for +5 min. */
    private static Instant slot() {
        Instant candidate = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        Instant cap = LocalDate.now(ZoneOffset.UTC).atTime(23, 0).toInstant(ZoneOffset.UTC);
        return candidate.isAfter(cap) ? Instant.now().plus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES) : candidate;
    }

    private ResponseEntity<Map> book(Ctx c, String patientId, Instant start) {
        return exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "doctorId", c.staff().id().toString(), "patientId", patientId, "startTime", start.toString())), Map.class);
    }

    private ResponseEntity<Map> checkIn(String token, Ctx c, String patientId, String appointmentId) {
        return exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "appointmentId", appointmentId, "patientId", patientId, "doctorId", c.staff().id().toString())), Map.class);
    }

    private static long count(List<ResponseEntity<Map>> rs, HttpStatus s) {
        return rs.stream().filter(r -> r.getStatusCode() == s).count();
    }

    @Test
    void doubleCheckInOfOneAppointmentMintsOneToken() {
        Ctx c = setup();
        allDayWorkingHours(c);
        String patientId = registerPatient(c.token());
        String apptId = (String) book(c, patientId, slot()).getBody().get("id");

        List<ResponseEntity<Map>> rs = ConcurrencyHarness.raceSame(2, () -> checkIn(c.token(), c, patientId, apptId));

        assertThat(count(rs, HttpStatus.CREATED)).isEqualTo(1);
        ResponseEntity<Map> loser = rs.stream().filter(r -> r.getStatusCode() != HttpStatus.CREATED).findFirst().orElseThrow();
        assertThat(loser.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(loser.getBody().get("type")).asString().contains("already-checked-in");
        Integer entries = inTenantTx(c.tenant().id(), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM queue_entries WHERE appointment_id = ?", Integer.class, UUID.fromString(apptId)));
        assertThat(entries).isEqualTo(1);
    }

    @Test
    void concurrentPaymentsKeepPaidEqualToSumOfPayments() {
        Ctx c = setup();
        String patientId = registerPatient(c.token());
        ResponseEntity<Map> ci = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "patientId", patientId, "doctorId", c.staff().id().toString())), Map.class);
        String entry = (String) ci.getBody().get("id");
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending")) {
            exchange("/v1/queue/" + entry + "/status", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("status", s)), Map.class);
        }
        ResponseEntity<Map> inv = exchange("/v1/billing/checkout/" + entry, HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "lineItems", List.of(Map.of("override", Map.of("reasonCode", "other"), "chargeCode", "X", "chargeName", "X", "category", "Service",
                        "quantity", 1, "unitPrice", 500, "taxRatePercent", 0)))), Map.class);
        assertThat(inv.getStatusCode()).isEqualTo(HttpStatus.OK);
        String invoiceId = (String) inv.getBody().get("id");

        List<ResponseEntity<Map>> rs = ConcurrencyHarness.race(List.of(
                () -> pay(c, invoiceId, 300), () -> pay(c, invoiceId, 200)));

        assertThat(rs).allMatch(r -> r.getStatusCode() == HttpStatus.CREATED); // FIN-001: payment is 201
        Map<String, Object> row = inTenantTx(c.tenant().id(), () -> jdbc.queryForMap(
                "SELECT i.paid, i.status, (SELECT SUM(amount) FROM invoice_payments WHERE invoice_id = i.id) AS summed FROM invoices i WHERE i.id = ?",
                UUID.fromString(invoiceId)));
        assertThat((BigDecimal) row.get("summed")).isEqualByComparingTo("500");
        assertThat((BigDecimal) row.get("paid")).isEqualByComparingTo("500");
        assertThat(row.get("status")).isEqualTo("paid");
    }

    private ResponseEntity<Map> pay(Ctx c, String invoiceId, int amount) {
        return exchange("/v1/billing/invoices/" + invoiceId + "/payments", HttpMethod.POST,
                authedJsonBody(c.token(), Map.of("method", "cash", "amount", amount)), Map.class);
    }

    @Test
    void overlappingBookingsForOneDoctorAllowExactlyOne() {
        Ctx c = setup();
        allDayWorkingHours(c);
        String a = registerPatient(c.token());
        String b = registerPatient(c.token());
        Instant t = slot();

        List<ResponseEntity<Map>> rs = ConcurrencyHarness.race(List.of(
                () -> book(c, a, t), () -> book(c, b, t.plus(5, ChronoUnit.MINUTES))));

        assertThat(count(rs, HttpStatus.CREATED)).isEqualTo(1);
    }

    @Test
    void sameSlotBookingsAllowExactlyOne() {
        Ctx c = setup();
        allDayWorkingHours(c);
        String a = registerPatient(c.token());
        String b = registerPatient(c.token());
        Instant t = slot();

        List<ResponseEntity<Map>> rs = ConcurrencyHarness.race(List.of(() -> book(c, a, t), () -> book(c, b, t)));

        assertThat(count(rs, HttpStatus.CREATED)).isEqualTo(1);
        assertThat(count(rs, HttpStatus.CONFLICT)).isEqualTo(1);
    }

    @Test
    void tenConcurrentStatusTransitionsFromOneStateSucceedOnce() {
        Ctx c = setup();
        String patientId = registerPatient(c.token());
        String entry = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "patientId", patientId, "doctorId", c.staff().id().toString())), Map.class).getBody().get("id");

        List<ResponseEntity<Map>> rs = ConcurrencyHarness.raceSame(10, () -> exchange("/v1/queue/" + entry + "/status",
                HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("status", "waiting", "expectedStatus", "checked_in")), Map.class)); // ACC-002: compare-and-set names the stage the screen shows

        assertThat(count(rs, HttpStatus.OK)).isEqualTo(1);
        assertThat(count(rs, HttpStatus.CONFLICT)).isEqualTo(9);
    }

    @Test
    @Tag("known-defect-audit-chain-fork")
    void fiftyParallelAuditedWritesKeepAnUnforkedChain() {
        Ctx c = setup();
        UUID tenantId = c.tenant().id();

        List<Boolean> done = ConcurrencyHarness.raceSame(50, () -> {
            inTenantTx(tenantId, () -> auditService.record(tenantId, "system", null, "System", "System", null,
                    "conc.write", "conc", UUID.randomUUID(), null, Map.of("k", "v")));
            return true;
        });

        assertThat(done).hasSize(50);
        Integer rows = inTenantTx(tenantId, () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'conc.write'", Integer.class));
        assertThat(rows).isEqualTo(50);
        Long broken = inTenantTx(tenantId, () -> jdbc.queryForObject("SELECT verify_audit_chain(?)", Long.class, tenantId));
        assertThat(broken).isNull();
    }

    @Test
    @Tag("known-defect-refund-approve-race")
    void twoApproversOfOneRefundIssueOneCreditNote() {
        Ctx c = setup();
        UUID roleId = c.roleId();
        String t1 = loginAndGetAccessToken(seedStaff(c.tenant(), roleId, "ap1" + UUID.randomUUID() + "@a.com", "+919511111111", false));
        String t2 = loginAndGetAccessToken(seedStaff(c.tenant(), roleId, "ap2" + UUID.randomUUID() + "@a.com", "+919522222222", false));
        String patientId = registerPatient(c.token());
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "name", "Conc Pkg", "packageType", "combination", "speciality", "Dermatology", "description", "d",
                "price", 2400.00, "taxInclusive", false, "validityDays", 90, "validityStarts", "purchase_date"));
        body.put("graceDays", 7);
        body.put("items", List.of(
                Map.of("itemType", "service_session", "name", "Facial", "quantity", 2, "unitListPrice", 1000.00, "taxRatePercent", 18.0),
                Map.of("itemType", "take_home_product", "name", "Cream", "quantity", 1, "unitListPrice", 1000.00, "taxRatePercent", 12.0)));
        ResponseEntity<Map> pkg = exchange("/v1/packages", HttpMethod.POST, authedJsonBody(c.token(), body), Map.class);
        String packageId = (String) pkg.getBody().get("id");
        exchange("/v1/packages/" + packageId + "/activate", HttpMethod.POST, authed(c.token()), Map.class);
        String instanceId = (String) exchange("/v1/packages/sell", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "patientId", patientId, "packageId", packageId, "paymentMethod", "upi")), Map.class).getBody().get("id");
        String refundId = (String) exchange("/v1/packages/instances/" + instanceId + "/refund", HttpMethod.POST,
                authedJsonBody(c.token(), Map.of("reason", "Patient relocated")), Map.class).getBody().get("id");

        List<ResponseEntity<Map>> rs = ConcurrencyHarness.race(List.of(
                () -> exchange("/v1/packages/refunds/" + refundId + "/approve", HttpMethod.POST, authed(t1), Map.class),
                () -> exchange("/v1/packages/refunds/" + refundId + "/approve", HttpMethod.POST, authed(t2), Map.class)));

        assertThat(count(rs, HttpStatus.OK)).isEqualTo(1);
        Integer approvals = inTenantTx(c.tenant().id(), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = 'packages.refund_approved'", Integer.class, UUID.fromString(refundId)));
        assertThat(approvals).isEqualTo(1);
    }
}
