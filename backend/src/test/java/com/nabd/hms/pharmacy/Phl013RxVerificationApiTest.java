package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.pharmacy.api.StockCommands;
import com.nabd.hms.pharmacy.api.StockCommands.BatchReceipt;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ClinicianFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** PHL-013: the printout's signed QR (print-header) and POST /v1/pharmacy/rx-verifications. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Phl013RxVerificationApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000) + 700_000);

    @Autowired
    private StockCommands stock;
    @Autowired
    private OutboxDispatcher dispatcher;

    record Clinic(SeededTenant tenant, SeededStaff staff, String token) {
        UUID t() {
            return tenant.id();
        }
    }

    private static String phone() {
        return "+9197" + String.format("%08d", SEQ.incrementAndGet());
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private Clinic clinic() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = ClinicianFixture.licensed(seedStaff(tenant, seedFullAccessRole(tenant.id()), "p13" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        su().update("UPDATE staff SET name = 'Dr Rao' WHERE id = ?", staff.id());
        String token = loginAndGetAccessToken(staff);
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "in_house", "reason", "PHL-013 test")),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Clinic(tenant, staff, token);
    }

    private String pharmacist(Clinic c) {
        UUID role = seedRole(c.t(), "Pharmacist " + SEQ.incrementAndGet(), false, new ModuleGrant("pharmacy", true, true, true, false, false, false, false));
        return loginAndGetAccessToken(seedStaff(c.tenant(), role, "ph" + SEQ.incrementAndGet() + "@q.com", phone(), false));
    }

    private UUID item(Clinic c, String name, String scheduleClass) {
        ResponseEntity<Map> r = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(c.token(), Map.of("name", name,
                "isRx", !"OTC".equals(scheduleClass), "price", 10.00, "taxRatePercent", 12, "scheduleClass", scheduleClass, "inHouse", true)), Map.class);
        assertThat(r.getStatusCode()).as("item %s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString((String) r.getBody().get("id"));
        inTenantTx(c.t(), () -> stock.receiveBatch(id, new BatchReceipt("B" + SEQ.incrementAndGet(), LocalDate.now().plusDays(400),
                new BigDecimal("12.00"), new BigDecimal("7.50"), 50, null, null, null), c.staff().id(), "GRN"));
        return id;
    }

    private String visit(Clinic c) {
        String patient = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), Map.of("name", "Scan Patient", "phone", phone(),
                "dob", "1970-01-01", "gender", "female")), Map.class).getBody().get("id");
        return (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "patientId", patient, "doctorId", c.staff().id().toString())), Map.class).getBody().get("id");
    }

    private List<String> sign(Clinic c, String qe, Object[]... lines) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object[] l : lines) items.add(Map.of("drugName", l[0], "dosage", "1 tab", "frequency", "BD", "quantity", l[1], "quantityUnit", "tablets"));
        ResponseEntity<Map> saved = exchange("/v1/clinical/prescriptions/" + qe, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("items", items)), Map.class);
        assertThat(saved.getStatusCode()).as("rx %s", saved.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/clinical/prescriptions/" + qe + "/sign", HttpMethod.POST, authed(c.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        for (int i = 0; i < 5; i++) dispatcher.poll(); // the worklist entry
        List<String> ids = new ArrayList<>();
        for (Object o : (List<?>) saved.getBody().get("items")) ids.add((String) ((Map<?, ?>) o).get("id"));
        return ids;
    }

    private Map<String, Object> printHeader(Clinic c, String qe) {
        ResponseEntity<Map> r = exchange("/v1/clinical/prescriptions/" + qe + "/print-header", HttpMethod.GET, authed(c.token()), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private ResponseEntity<Map> verify(String token, String payload) {
        return exchange("/v1/pharmacy/rx-verifications", HttpMethod.POST, authedJsonBody(token, Map.of("qrPayload", payload)), Map.class);
    }

    private Map<String, Object> verified(String token, String payload) {
        ResponseEntity<Map> r = verify(token, payload);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private static String slug(ResponseEntity<Map> r) {
        String type = String.valueOf(r.getBody().get("type"));
        return type.substring(type.lastIndexOf('/') + 1);
    }

    private int scans(Clinic c, String result) {
        return su().queryForObject("SELECT count(*) FROM rx_verifications WHERE tenant_id = ? AND result = ?", Integer.class, c.t(), result);
    }

    // ---------------- AC1: a valid QR opens the verified Rx with prescriber name and registration number ----------------

    @Test
    void aValidQrFromThisClinicVerifiesWithPrescriberAndOpensTheWorklistEntry() {
        Clinic c = clinic();
        item(c, "Vq amox", "OTC");
        String qe = visit(c);
        List<String> rx = sign(c, qe, new Object[]{"Vq amox", 10}, new Object[]{"Vq outside", 1});
        Map<String, Object> header = printHeader(c, qe);
        String qr = (String) header.get("qrPayload");
        assertThat(qr).startsWith("NRX1.").doesNotContain("Rao").doesNotContain("amox");
        assertThat(qr.getBytes().length).as("fits the in-house QR encoder").isLessThanOrEqualTo(213);
        assertThat((String) header.get("qrSvg")).startsWith("<svg").contains("<path");

        String ph = pharmacist(c);
        Map<String, Object> v = verified(ph, qr);
        assertThat(v).containsEntry("status", "valid").containsEntry("prescriptionId", header.get("prescriptionId")).containsEntry("queueEntryId", qe)
                .containsEntry("validUntil", null).containsEntry("dispensed", null);
        assertThat((Map<String, Object>) v.get("prescriber")).containsEntry("name", "Dr Rao").containsEntry("regNo", header.get("professionalRegistrationNo"));
        assertThat(v.get("worklistEntryId")).isEqualTo(su().queryForObject("SELECT id::text FROM pharmacy_worklist_entries WHERE prescription_id = ?::uuid",
                String.class, header.get("prescriptionId")));
        List<Map<String, Object>> remaining = (List<Map<String, Object>>) v.get("remaining");
        assertThat(remaining).extracting(l -> l.get("prescriptionItemId")).containsExactly(rx.get(0), rx.get(1));
        assertThat(((Number) remaining.get(0).get("remaining")).doubleValue()).isEqualTo(10.0);
        assertThat(scans(c, "valid")).isEqualTo(1);

        // the public keys the browser caches: the signing kid, no private part
        ResponseEntity<Map> keys = exchange("/v1/pharmacy/rx-verifications/keys", HttpMethod.GET, authed(ph), Map.class);
        assertThat(keys.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> jwks = (List<Map<String, Object>>) keys.getBody().get("keys");
        assertThat(jwks).isNotEmpty().allSatisfy(k -> assertThat(k).containsEntry("kty", "EC").containsEntry("crv", "P-256").containsKey("kid").doesNotContainKey("d"));

        // a reception role without pharmacy:dispense cannot verify
        UUID desk = seedRole(c.t(), "Desk " + SEQ.incrementAndGet(), false, new ModuleGrant("patients", true, true, false, false, false, false, false));
        String deskToken = loginAndGetAccessToken(seedStaff(c.tenant(), desk, "d" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        assertThat(verify(deskToken, qr).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------------- AC2: an altered payload is "signature invalid" with no Rx content ----------------

    @Test
    void anAlteredPayloadIsSignatureInvalidAndCarriesNoContent() {
        Clinic c = clinic();
        item(c, "Tq drug", "OTC");
        String qe = visit(c);
        sign(c, qe, new Object[]{"Tq drug", 3});
        String qr = (String) printHeader(c, qe).get("qrPayload");
        String[] f = qr.split("\\.", -1);
        f[3] = Long.toString(Long.parseLong(f[3]) + 86_400); // signed a day later than it was
        String ph = pharmacist(c);
        Map<String, Object> v = verified(ph, String.join(".", f));
        assertThat(v).containsEntry("status", "invalid").containsEntry("reason", "signature").containsEntry("prescriptionId", null)
                .containsEntry("prescriber", null).containsEntry("worklistEntryId", null);
        assertThat((List<?>) v.get("remaining")).isEmpty();
        assertThat(scans(c, "invalid")).isEqualTo(1);

        ResponseEntity<Map> junk = verify(ph, "https://example.com/not-an-rx");
        assertThat(junk.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(slug(junk)).isEqualTo("qr-unreadable");
        assertThat(verify(ph, "").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------------- AC3: signed 6 days ago with a 5-day validity -> expired on <date> ----------------

    @Test
    void anRxPastItsScheduleClassValidityIsExpiredWithTheDate() {
        Clinic c = clinic();
        // Test value only: real periods need clinical safety officer sign-off (the migration sets none).
        su().update("UPDATE pharmacy_schedule_rules SET rx_validity_days = 5 WHERE market = 'IN' AND schedule_class = 'IN_H'");
        try {
            item(c, "Ex antibiotic", "IN_H");
            String qe = visit(c);
            sign(c, qe, new Object[]{"Ex antibiotic", 10});
            Map<String, Object> header = printHeader(c, qe);
            String qr = (String) header.get("qrPayload");
            Instant signedAt = Instant.parse((String) header.get("signedAt"));
            assertThat(Instant.ofEpochSecond(Long.parseLong(qr.split("\\.")[4]))).as("the QR carries the validity")
                    .isEqualTo(signedAt.plus(Duration.ofDays(5)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
            String ph = pharmacist(c);
            assertThat(verified(ph, qr)).containsEntry("status", "valid");

            // Six days later (a signed prescription row is immutable, so the clock moves on the worklist entry, as Phl012 does).
            su().update("UPDATE pharmacy_worklist_entries SET valid_until = signed_at - interval '1 day' WHERE prescription_id = ?::uuid", header.get("prescriptionId"));
            Map<String, Object> v = verified(ph, qr);
            assertThat(v).containsEntry("status", "expired").containsEntry("prescriptionId", header.get("prescriptionId"));
            assertThat(Duration.between(signedAt.minus(Duration.ofDays(1)), Instant.parse((String) v.get("validUntil"))).abs()).as("expired on signed + 5 d")
                    .isLessThan(Duration.ofSeconds(1));
            assertThat((Map<String, Object>) v.get("prescriber")).containsEntry("name", "Dr Rao");
            assertThat(scans(c, "expired")).isEqualTo(1);
        } finally {
            su().update("UPDATE pharmacy_schedule_rules SET rx_validity_days = NULL WHERE market = 'IN' AND schedule_class = 'IN_H'");
        }
    }

    // ---------------- AC4: fully dispensed -> when and where ----------------

    @Test
    void aFullyDispensedRxSaysWhenAndWhereItWasDispensed() {
        Clinic c = clinic();
        UUID drug = item(c, "Dq drug", "OTC");
        String qe = visit(c);
        List<String> rx = sign(c, qe, new Object[]{"Dq drug", 4});
        String qr = (String) printHeader(c, qe).get("qrPayload");
        String code = su().queryForObject("SELECT code FROM charge_catalogue WHERE id = ?", String.class, drug);
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending")) {
            exchange("/v1/queue/" + qe + "/status", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("status", s)), Map.class);
        }
        ResponseEntity<Map> bill = exchange("/v1/billing/checkout/" + qe, HttpMethod.POST, authedJsonBody(c.token(), Map.of("lineItems", List.of(
                Map.of("chargeCode", code, "quantity", 4, "prescriptionItemId", rx.get(0))))), Map.class);
        assertThat(bill.getStatusCode()).as("%s", bill.getBody()).isEqualTo(HttpStatus.OK);

        Map<String, Object> v = verified(pharmacist(c), qr);
        assertThat(v).containsEntry("status", "dispensed");
        assertThat((List<?>) v.get("remaining")).isEmpty();
        Map<String, Object> d = (Map<String, Object>) v.get("dispensed");
        assertThat(Instant.parse((String) d.get("at"))).isAfter(Instant.now().minus(Duration.ofMinutes(5)));
        assertThat((String) d.get("where")).contains("Test Clinic").contains((String) bill.getBody().get("invoiceNumber"));
    }

    // ---------------- another clinic's QR: "not from this clinic", no content ----------------

    @Test
    void anotherClinicsQrIsRefusedWithoutContent() {
        Clinic a = clinic(), b = clinic();
        item(a, "Xq drug", "OTC");
        String qe = visit(a);
        sign(a, qe, new Object[]{"Xq drug", 2});
        String qr = (String) printHeader(a, qe).get("qrPayload");
        Map<String, Object> v = verified(pharmacist(b), qr);
        assertThat(v).containsEntry("status", "invalid").containsEntry("reason", "other-tenant").containsEntry("prescriptionId", null).containsEntry("prescriber", null);
        assertThat(scans(b, "invalid")).isEqualTo(1);
        assertThat(scans(a, "invalid")).isZero();
    }
}
