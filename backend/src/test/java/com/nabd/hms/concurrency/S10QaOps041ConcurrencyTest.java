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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA (S10) for OPS-041. Written from the story's acceptance criteria; every race runs on a released-together thread pool. */
class S10QaOps041ConcurrencyTest extends ApiTestBase {

    @Autowired AuditService auditService;

    private static final AtomicInteger SEQ = new AtomicInteger(20_000_000);

    private record Ctx(SeededTenant t, UUID role, SeededStaff doctor, String token) { }

    private Ctx ctx() {
        SeededTenant t = seedTenant();
        jdbc.update("UPDATE tenants SET timezone = 'UTC' WHERE id = ?", t.id());
        UUID role = seedFullAccessRole(t.id());
        SeededStaff d = seedStaff(t, role, "s10q41" + UUID.randomUUID() + "@a.com", "+9194" + (10_000_000 + SEQ.incrementAndGet() % 80_000_000), false);
        return new Ctx(t, role, d, loginAndGetAccessToken(d));
    }

    private String patient(Ctx c) {
        int i = SEQ.incrementAndGet();
        var r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token, Map.of("name", "S10Q41 " + i, "phone", "+9196" + i, "dob", LocalDate.of(1941, 1, 1).plusDays(i % 20000).toString(), "gender", "female")), Map.class);
        assertThat(r.getStatusCode()).as("register " + r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private void allDayHours(Ctx c, SeededStaff doc, String token) {
        for (int dow = 0; dow < 7; dow++)
            exchange("/v1/doctors/" + doc.id() + "/working-hours", HttpMethod.POST, authedJsonBody(token, Map.of("dayOfWeek", dow, "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
    }

    private static Instant futureSlot(int plusQuarterHours) {
        return Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).plus(10, ChronoUnit.HOURS).plus(15L * plusQuarterHours, ChronoUnit.MINUTES);
    }

    private ResponseEntity<Map> book(String token, UUID doctor, String patient, Instant start) {
        return exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(token, Map.of("doctorId", doctor.toString(), "patientId", patient, "startTime", start.toString())), Map.class);
    }

    private UUID apptToday(Ctx c, String patient, int i) {
        UUID id = UUID.randomUUID();
        Instant start = LocalDate.now(ZoneOffset.UTC).atTime(12, 0).toInstant(ZoneOffset.UTC).plusSeconds(10L * i);
        try (Connection su = superuserConnection(); PreparedStatement ps = su.prepareStatement(
                "INSERT INTO appointments (id, tenant_id, patient_id, doctor_id, start_time, end_time, status) VALUES (?,?,?,?,?,?,'scheduled')")) {
            ps.setObject(1, id); ps.setObject(2, c.t.id()); ps.setObject(3, UUID.fromString(patient)); ps.setObject(4, c.doctor.id());
            ps.setTimestamp(5, Timestamp.from(start)); ps.setTimestamp(6, Timestamp.from(start.plusSeconds(1)));
            ps.executeUpdate();
        } catch (Exception e) { throw new IllegalStateException(e); }
        return id;
    }

    private ResponseEntity<Map> checkIn(String token, Ctx c, String patient, UUID appt) {
        return exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of("patientId", patient, "doctorId", c.doctor.id().toString(), "appointmentId", appt.toString())), Map.class);
    }

    private static long n(List<ResponseEntity<Map>> rs, HttpStatus s) { return rs.stream().filter(r -> r.getStatusCode() == s).count(); }

    // ---- AC1: double check-in ----

    @Test
    void ac1_twoReceptionistsCheckInTheSameAppointment_exactlyOne201_otherIs409_oneQueueEntry() {
        Ctx c = ctx();
        String second = loginAndGetAccessToken(seedStaff(c.t, c.role, "recep" + UUID.randomUUID() + "@a.com", "+9193" + (10_000_000 + SEQ.incrementAndGet() % 80_000_000), false));
        String p = patient(c);
        UUID a = apptToday(c, p, 0);

        List<ResponseEntity<Map>> rs = ConcurrencyHarness.race(List.of((Callable<ResponseEntity<Map>>) () -> checkIn(c.token, c, p, a), () -> checkIn(second, c, p, a)));

        assertThat(n(rs, HttpStatus.CREATED)).as(rs.toString()).isEqualTo(1);
        ResponseEntity<Map> loser = rs.stream().filter(r -> r.getStatusCode() != HttpStatus.CREATED).findFirst().orElseThrow();
        assertThat(loser.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(loser.getBody().get("type")).toLowerCase()).containsAnyOf("already-checked-in", "already_arrived", "already-arrived");
        assertThat(inTenantTx(c.t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM queue_entries WHERE appointment_id = ?", Integer.class, a))).isEqualTo(1);
    }

    @Test
    void ac1_eightThreadsOneAppointment_oneEntryOneToken() {
        Ctx c = ctx();
        String p = patient(c);
        UUID a = apptToday(c, p, 0);
        List<ResponseEntity<Map>> rs = ConcurrencyHarness.raceSame(8, () -> checkIn(c.token, c, p, a));
        assertThat(n(rs, HttpStatus.CREATED)).isEqualTo(1);
        assertThat(n(rs, HttpStatus.CONFLICT)).isEqualTo(7);
        assertThat(inTenantTx(c.t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM queue_entries WHERE patient_id = ?::uuid", Integer.class, p))).isEqualTo(1);
    }

    @Test
    void ac1_differentAppointmentsCheckedInAtOnce_allSucceedWithDistinctTokens() {
        Ctx c = ctx();
        List<Callable<ResponseEntity<Map>>> work = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String p = patient(c);
            UUID a = apptToday(c, p, i);
            work.add(() -> checkIn(c.token, c, p, a));
        }
        List<ResponseEntity<Map>> rs = ConcurrencyHarness.race(work);
        assertThat(n(rs, HttpStatus.CREATED)).as(rs.toString()).isEqualTo(6);
        assertThat(inTenantTx(c.t.id(), () -> jdbc.queryForObject("SELECT count(*) || '/' || count(DISTINCT token_number) FROM queue_entries", String.class))).isEqualTo("6/6");
    }

    @Test
    void ac1_otherTenantsCheckInIsNotBlockedOrCounted() {
        Ctx a = ctx(), b = ctx();
        String pa = patient(a), pb = patient(b);
        UUID aa = apptToday(a, pa, 0), ab = apptToday(b, pb, 0);
        List<ResponseEntity<Map>> rs = ConcurrencyHarness.race(List.of((Callable<ResponseEntity<Map>>) () -> checkIn(a.token, a, pa, aa), () -> checkIn(b.token, b, pb, ab)));
        assertThat(n(rs, HttpStatus.CREATED)).isEqualTo(2);
    }

    // ---- AC2: payments ----

    private String invoice(Ctx c, int total) {
        String p = patient(c);
        String entry = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token, Map.of("patientId", p, "doctorId", c.doctor.id().toString())), Map.class).getBody().get("id");
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending"))
            exchange("/v1/queue/" + entry + "/status", HttpMethod.PATCH, authedJsonBody(c.token, Map.of("status", s)), Map.class);
        var inv = exchange("/v1/billing/checkout/" + entry, HttpMethod.POST, authedJsonBody(c.token, Map.of("lineItems", List.of(
                Map.of("chargeCode", "S10Q", "chargeName", "Consultation", "category", "Service", "quantity", 1, "unitPrice", total, "taxRatePercent", 0)))), Map.class);
        assertThat(inv.getStatusCode()).as("checkout " + inv.getBody()).isEqualTo(HttpStatus.OK);
        return (String) inv.getBody().get("id");
    }

    private ResponseEntity<Map> pay(Ctx c, String invoice, int amount) {
        return exchange("/v1/billing/invoices/" + invoice + "/payments", HttpMethod.POST, authedJsonBody(c.token, Map.of("method", "cash", "amount", amount)), Map.class);
    }

    private Map<String, Object> inv(Ctx c, String invoice) {
        return inTenantTx(c.t.id(), () -> jdbc.queryForMap("SELECT i.paid, i.total, i.status, COALESCE((SELECT SUM(amount) FROM invoice_payments WHERE invoice_id = i.id), 0) AS summed FROM invoices i WHERE i.id = ?", UUID.fromString(invoice)));
    }

    @Test
    void ac2_payments300And200On500_paidEquals500EqualsSum_statusPaid() {
        Ctx c = ctx();
        String invoice = invoice(c, 500);
        List<ResponseEntity<Map>> rs = ConcurrencyHarness.race(List.of((Callable<ResponseEntity<Map>>) () -> pay(c, invoice, 300), () -> pay(c, invoice, 200)));
        assertThat(rs).allMatch(r -> r.getStatusCode() == HttpStatus.CREATED, rs.toString());
        Map<String, Object> row = inv(c, invoice);
        assertThat((BigDecimal) row.get("summed")).isEqualByComparingTo("500");
        assertThat((BigDecimal) row.get("paid")).isEqualByComparingTo("500");
        assertThat(row.get("status")).isEqualTo("paid");
    }

    @Test
    void ac2_tenPaymentsOf100On1000_noLostUpdate() {
        Ctx c = ctx();
        String invoice = invoice(c, 1000);
        List<ResponseEntity<Map>> rs = ConcurrencyHarness.raceSame(10, () -> pay(c, invoice, 100));
        assertThat(n(rs, HttpStatus.CREATED)).as(rs.toString()).isEqualTo(10);
        Map<String, Object> row = inv(c, invoice);
        assertThat((BigDecimal) row.get("paid")).isEqualByComparingTo("1000");
        assertThat((BigDecimal) row.get("summed")).isEqualByComparingTo("1000");
        assertThat(row.get("status")).isEqualTo("paid");
    }

    @Test
    void ac2_overpaymentRace_neverExceedsTotal_andPaidAlwaysEqualsSum() {
        Ctx c = ctx();
        String invoice = invoice(c, 500);
        List<ResponseEntity<Map>> rs = ConcurrencyHarness.race(List.of((Callable<ResponseEntity<Map>>) () -> pay(c, invoice, 400), () -> pay(c, invoice, 400)));
        Map<String, Object> row = inv(c, invoice);
        assertThat((BigDecimal) row.get("paid")).as("responses " + rs).isEqualByComparingTo((BigDecimal) row.get("summed"));
        assertThat(((BigDecimal) row.get("paid")).compareTo((BigDecimal) row.get("total"))).as("paid must not exceed total; responses " + rs).isLessThanOrEqualTo(0);
        assertThat(rs.stream().filter(r -> r.getStatusCode().is5xxServerError())).isEmpty();
    }

    // ---- AC3: booking overlap ----

    @Test
    void ac3_overlappingBookings_10_00And10_05_exactlyOneSucceeds_noServerError() {
        Ctx c = ctx();
        allDayHours(c, c.doctor, c.token);
        String a = patient(c), b = patient(c);
        Instant t = futureSlot(0);
        List<ResponseEntity<Map>> rs = ConcurrencyHarness.race(List.of((Callable<ResponseEntity<Map>>) () -> book(c.token, c.doctor.id(), a, t), () -> book(c.token, c.doctor.id(), b, t.plus(5, ChronoUnit.MINUTES))));
        assertThat(n(rs, HttpStatus.CREATED)).as(rs.toString()).isEqualTo(1);
        assertThat(rs.stream().filter(r -> r.getStatusCode().is5xxServerError())).isEmpty();
        assertThat(inTenantTx(c.t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM appointments WHERE doctor_id = ? AND status = 'scheduled'", Integer.class, c.doctor.id()))).isEqualTo(1);
    }

    @Test
    void ac3_sixThreadsSameSlot_oneWins() {
        Ctx c = ctx();
        allDayHours(c, c.doctor, c.token);
        List<String> ps = new ArrayList<>();
        for (int i = 0; i < 6; i++) ps.add(patient(c));
        Instant t = futureSlot(1);
        List<Callable<ResponseEntity<Map>>> work = ps.stream().map(p -> (Callable<ResponseEntity<Map>>) () -> book(c.token, c.doctor.id(), p, t)).toList();
        List<ResponseEntity<Map>> rs = ConcurrencyHarness.race(work);
        assertThat(n(rs, HttpStatus.CREATED)).as(rs.toString()).isEqualTo(1);
        assertThat(n(rs, HttpStatus.CONFLICT)).isEqualTo(5);
    }

    @Test
    void ac3_adjacentSlotsAndDifferentDoctors_doNotConflict() {
        Ctx c = ctx();
        SeededStaff d2 = seedStaff(c.t, c.role, "d2" + UUID.randomUUID() + "@a.com", "+9192" + (10_000_000 + SEQ.incrementAndGet() % 80_000_000), false);
        allDayHours(c, c.doctor, c.token);
        allDayHours(c, d2, c.token);
        String a = patient(c), b = patient(c), cc = patient(c);
        Instant t = futureSlot(2);
        List<ResponseEntity<Map>> rs = ConcurrencyHarness.race(List.of(
                (Callable<ResponseEntity<Map>>) () -> book(c.token, c.doctor.id(), a, t),
                () -> book(c.token, c.doctor.id(), b, t.plus(15, ChronoUnit.MINUTES)),   // adjacent, not overlapping
                () -> book(c.token, d2.id(), cc, t)));                                    // same time, other doctor
        assertThat(n(rs, HttpStatus.CREATED)).as(rs.toString()).isEqualTo(3);
    }

    // ---- AC4: status transitions ----

    @Test
    void ac4_tenConcurrentTransitionsFromTheSameState_oneOk_nineConflict() {
        Ctx c = ctx();
        String p = patient(c);
        String entry = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token, Map.of("patientId", p, "doctorId", c.doctor.id().toString())), Map.class).getBody().get("id");
        List<ResponseEntity<Map>> rs = ConcurrencyHarness.raceSame(10, () -> exchange("/v1/queue/" + entry + "/status", HttpMethod.PATCH,
                authedJsonBody(c.token, Map.of("status", "waiting", "expectedStatus", "checked_in")), Map.class));
        assertThat(n(rs, HttpStatus.OK)).as(rs.toString()).isEqualTo(1);
        assertThat(n(rs, HttpStatus.CONFLICT)).isEqualTo(9);
        assertThat(inTenantTx(c.t.id(), () -> jdbc.queryForObject("SELECT status FROM queue_entries WHERE id = ?::uuid", String.class, entry))).isEqualTo("waiting");
    }

    @Test
    void ac4_staleExpectedStatusIsRefusedWith409_notAppliedSilently() {
        Ctx c = ctx();
        String p = patient(c);
        String entry = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token, Map.of("patientId", p, "doctorId", c.doctor.id().toString())), Map.class).getBody().get("id");
        exchange("/v1/queue/" + entry + "/status", HttpMethod.PATCH, authedJsonBody(c.token, Map.of("status", "waiting")), Map.class);
        var stale = exchange("/v1/queue/" + entry + "/status", HttpMethod.PATCH, authedJsonBody(c.token, Map.of("status", "waiting", "expectedStatus", "checked_in")), Map.class);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ---- AC5: audit chain ----

    @Test
    @Tag("known-defect-audit-chain-fork")
    void ac5_fiftyParallelAuditedWritesViaTheService_noFork() {
        Ctx c = ctx();
        UUID tid = c.t.id();
        ConcurrencyHarness.raceSame(50, () -> {
            inTenantTx(tid, () -> auditService.record(tid, "system", null, "System", "System", null, "s10qa.write", "s10qa", UUID.randomUUID(), null, Map.of("k", "v")));
            return true;
        });
        assertThat(inTenantTx(tid, () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 's10qa.write'", Integer.class))).isEqualTo(50);
        assertThat(inTenantTx(tid, () -> jdbc.queryForObject("SELECT verify_audit_chain(?)", Long.class, tid))).isNull();
    }

    @Test
    void ac5_fiftyParallelRealApiWritesInOneTenant_chainVerifies() {
        Ctx c = ctx();
        List<Callable<ResponseEntity<Map>>> work = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            int k = SEQ.incrementAndGet();
            work.add(() -> exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token, Map.of("name", java.util.stream.IntStream.range(0, 4).mapToObj(j -> Character.toString((char) ('a' + (k * 7 + j * 13 + j * j * k) % 26)).repeat(3 + j)).collect(java.util.stream.Collectors.joining(" ")) + k, "phone", "+9195" + k, "dob", LocalDate.of(1930, 1, 1).plusDays(k % 20000 * 3L).toString(), "gender", "male")), Map.class));
        }
        List<ResponseEntity<Map>> rs = ConcurrencyHarness.race(work);
        assertThat(n(rs, HttpStatus.CREATED)).as(rs.stream().filter(r -> r.getStatusCode() != HttpStatus.CREATED).toList().toString()).isEqualTo(50);
        assertThat(inTenantTx(c.t.id(), () -> jdbc.queryForObject("SELECT verify_audit_chain(?)", Long.class, c.t.id()))).isNull();
    }

    @Test
    @Tag("known-defect-audit-chain-fork")
    void ac5_parallelWritesInTwoTenantsKeepIndependentChains() {
        Ctx a = ctx(), b = ctx();
        List<Callable<Boolean>> work = new ArrayList<>();
        for (int i = 0; i < 25; i++) for (Ctx c : List.of(a, b)) work.add(() -> {
            inTenantTx(c.t.id(), () -> auditService.record(c.t.id(), "system", null, "System", "System", null, "s10qa.two", "s10qa", UUID.randomUUID(), null, Map.of("k", "v")));
            return true;
        });
        ConcurrencyHarness.race(work);
        for (Ctx c : List.of(a, b)) {
            assertThat(inTenantTx(c.t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 's10qa.two'", Integer.class))).isEqualTo(25);
            assertThat(inTenantTx(c.t.id(), () -> jdbc.queryForObject("SELECT verify_audit_chain(?)", Long.class, c.t.id()))).isNull();
        }
    }

    // ---- AC6: stability (never flaky, 0/20) ----

    @Test
    void ac6_checkInAndTransitionRaces_twentyRepeats_neverDiverge() {
        Ctx c = ctx();
        for (int i = 0; i < 20; i++) {
            String p = patient(c);
            UUID a = apptToday(c, p, i);
            List<ResponseEntity<Map>> rs = ConcurrencyHarness.raceSame(3, () -> checkIn(c.token, c, p, a));
            assertThat(n(rs, HttpStatus.CREATED)).as("rep " + i + " " + rs).isEqualTo(1);
            assertThat(n(rs, HttpStatus.CONFLICT)).as("rep " + i).isEqualTo(2);
            String entry = (String) rs.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).findFirst().orElseThrow().getBody().get("id");
            List<ResponseEntity<Map>> ts = ConcurrencyHarness.raceSame(4, () -> exchange("/v1/queue/" + entry + "/status", HttpMethod.PATCH,
                    authedJsonBody(c.token, Map.of("status", "waiting", "expectedStatus", "checked_in")), Map.class));
            assertThat(n(ts, HttpStatus.OK)).as("transition rep " + i + " " + ts).isEqualTo(1);
        }
    }

    // ---- refund approve race ----

    @Test
    @Tag("known-defect-refund-approve-race")
    void refundApprovedByThreeApproversAtOnce_isApprovedOnce() {
        Ctx c = ctx();
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 3; i++)
            tokens.add(loginAndGetAccessToken(seedStaff(c.t, c.role, "ap" + i + UUID.randomUUID() + "@a.com", "+9191" + (10_000_000 + SEQ.incrementAndGet() % 80_000_000), false)));
        String patientId = patient(c);
        Map<String, Object> body = new java.util.HashMap<>(Map.of("name", "S10Q Pkg", "packageType", "combination", "speciality", "Dermatology", "description", "d",
                "price", 2400.00, "taxInclusive", false, "validityDays", 90, "validityStarts", "purchase_date"));
        body.put("graceDays", 7);
        body.put("items", List.of(Map.of("itemType", "service_session", "name", "Facial", "quantity", 2, "unitListPrice", 1000.00, "taxRatePercent", 18.0),
                Map.of("itemType", "take_home_product", "name", "Cream", "quantity", 1, "unitListPrice", 1000.00, "taxRatePercent", 12.0)));
        String pkg = (String) exchange("/v1/packages", HttpMethod.POST, authedJsonBody(c.token, body), Map.class).getBody().get("id");
        exchange("/v1/packages/" + pkg + "/activate", HttpMethod.POST, authed(c.token), Map.class);
        String inst = (String) exchange("/v1/packages/sell", HttpMethod.POST, authedJsonBody(c.token, Map.of("patientId", patientId, "packageId", pkg, "paymentMethod", "upi")), Map.class).getBody().get("id");
        String refund = (String) exchange("/v1/packages/instances/" + inst + "/refund", HttpMethod.POST, authedJsonBody(c.token, Map.of("reason", "Patient relocated")), Map.class).getBody().get("id");

        List<Callable<ResponseEntity<Map>>> work = tokens.stream().map(t -> (Callable<ResponseEntity<Map>>) () -> exchange("/v1/packages/refunds/" + refund + "/approve", HttpMethod.POST, authed(t), Map.class)).toList();
        List<ResponseEntity<Map>> rs = ConcurrencyHarness.race(work);

        assertThat(n(rs, HttpStatus.OK)).as(rs.toString()).isEqualTo(1);
        assertThat(rs.stream().filter(r -> r.getStatusCode().is5xxServerError())).isEmpty();
        assertThat(inTenantTx(c.t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'packages.refund_approved'", Integer.class, UUID.fromString(refund)))).isEqualTo(1);
    }
}
