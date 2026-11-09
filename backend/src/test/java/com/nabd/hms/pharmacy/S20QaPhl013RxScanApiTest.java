package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.pharmacy.api.StockCommands;
import com.nabd.hms.pharmacy.api.StockCommands.BatchReceipt;
import com.nabd.hms.shared.rxsign.RxQr;
import com.nabd.hms.shared.rxsign.RxSigningKeys;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ClinicianFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** S20 QA (independent): PHL-013 edges: tampering variants, not-found with a live signature, partial dispense, expiry boundary, gates, input bounds, Hybrid. */
@SuppressWarnings({"unchecked", "rawtypes"})
class S20QaPhl013RxScanApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000) + 800_000);

    @Autowired StockCommands stock;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired RxSigningKeys keys;

    record Clinic(SeededTenant tenant, SeededStaff staff, String token) {
        UUID t() { return tenant.id(); }
    }

    private static String phone() { return "+9198" + String.format("%08d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000_000)); }

    private JdbcTemplate su() { return new JdbcTemplate(superuserDataSource()); }

    private Clinic clinic(String mode) {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = ClinicianFixture.licensed(seedStaff(tenant, seedFullAccessRole(tenant.id()), "q13" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        su().update("UPDATE staff SET name = 'Dr Qa Rao' WHERE id = ?", staff.id());
        String token = loginAndGetAccessToken(staff);
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", mode, "reason", "S20 QA")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Clinic(tenant, staff, token);
    }

    private String staffToken(Clinic c, ModuleGrant... grants) {
        UUID role = seedRole(c.t(), "R" + SEQ.incrementAndGet(), false, grants);
        return loginAndGetAccessToken(seedStaff(c.tenant(), role, "qs" + SEQ.incrementAndGet() + "@q.com", phone(), false));
    }

    private String pharmacist(Clinic c) { return staffToken(c, new ModuleGrant("pharmacy", true, true, true, false, false, false, false)); }

    private UUID item(Clinic c, String name, String cls) {
        ResponseEntity<Map> r = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(c.token(), Map.of("name", name,
                "isRx", !"OTC".equals(cls), "price", 10.00, "taxRatePercent", 12, "scheduleClass", cls, "inHouse", true)), Map.class);
        assertThat(r.getStatusCode()).as("item %s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString((String) r.getBody().get("id"));
        inTenantTx(c.t(), () -> stock.receiveBatch(id, new BatchReceipt("B" + SEQ.incrementAndGet(), LocalDate.now().plusDays(400),
                new BigDecimal("12.00"), new BigDecimal("7.50"), 50, null, null, null), c.staff().id(), "GRN"));
        return id;
    }

    private String visit(Clinic c) {
        ResponseEntity<Map> pr = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), Map.of("name", "Pt " + UUID.randomUUID().toString().substring(0, 8), "phone", phone(),
                "dob", (1950 + SEQ.incrementAndGet() % 50) + "-03-07", "gender", "female")), Map.class);
        assertThat(pr.getStatusCode()).as("patient %s", pr.getBody()).isEqualTo(HttpStatus.CREATED);
        String patient = (String) pr.getBody().get("id");
        return (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of("patientId", patient, "doctorId", c.staff().id().toString())), Map.class).getBody().get("id");
    }

    private List<String> sign(Clinic c, String qe, Object[]... lines) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object[] l : lines) items.add(Map.of("drugName", l[0], "dosage", "1 tab", "frequency", "BD", "quantity", l[1], "quantityUnit", "tablets"));
        ResponseEntity<Map> saved = exchange("/v1/clinical/prescriptions/" + qe, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("items", items)), Map.class);
        assertThat(saved.getStatusCode()).as("rx %s", saved.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/clinical/prescriptions/" + qe + "/sign", HttpMethod.POST, authed(c.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        for (int i = 0; i < 5; i++) dispatcher.poll();
        List<String> ids = new ArrayList<>();
        for (Object o : (List<?>) saved.getBody().get("items")) ids.add((String) ((Map<?, ?>) o).get("id"));
        return ids;
    }

    private Map<String, Object> header(Clinic c, String qe) {
        ResponseEntity<Map> r = exchange("/v1/clinical/prescriptions/" + qe + "/print-header", HttpMethod.GET, authed(c.token()), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private ResponseEntity<Map> verify(String token, Object body) {
        return exchange("/v1/pharmacy/rx-verifications", HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private Map<String, Object> verified(String token, String payload) {
        ResponseEntity<Map> r = verify(token, Map.of("qrPayload", payload));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private int scans(Clinic c) {
        return su().queryForObject("SELECT count(*) FROM rx_verifications WHERE tenant_id = ?", Integer.class, c.t());
    }

    private static void assertNoContent(Map<String, Object> v, String reason) {
        assertThat(v).containsEntry("status", "invalid").containsEntry("reason", reason).containsEntry("prescriptionId", null).containsEntry("queueEntryId", null)
                .containsEntry("worklistEntryId", null).containsEntry("prescriber", null).containsEntry("signedAt", null).containsEntry("validUntil", null).containsEntry("dispensed", null);
        assertThat((List<?>) v.get("remaining")).isEmpty();
    }

    private void bill(Clinic c, String qe, String code, int qty, String rxItem) {
        ResponseEntity<Map> bill = exchange("/v1/billing/checkout/" + qe, HttpMethod.POST, authedJsonBody(c.token(), Map.of("lineItems", List.of(
                Map.of("chargeCode", code, "quantity", qty, "prescriptionItemId", rxItem)))), Map.class);
        assertThat(bill.getStatusCode()).as("%s", bill.getBody()).isEqualTo(HttpStatus.OK);
    }

    private void toCheckout(Clinic c, String qe) {
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending")) {
            exchange("/v1/queue/" + qe + "/status", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("status", s)), Map.class);
        }
    }

    @Test
    void qApi02_everyTamperingVariantIsSignatureInvalidWithNoContent_andAWedgeNewlineStillVerifies() {
        Clinic c = clinic("in_house");
        item(c, "Qa amox", "OTC");
        String qe = visit(c);
        sign(c, qe, new Object[]{"Qa amox", 6});
        String qr = (String) header(c, qe).get("qrPayload");
        String ph = pharmacist(c);
        String[] f = qr.split("\\.", -1);
        // 1. one character of the signature changed (the first: the last base64url char only carries padding bits)
        char first = f[7].charAt(0);
        String[] g = f.clone();
        g[7] = (first == 'A' ? 'B' : 'A') + f[7].substring(1);
        assertNoContent(verified(ph, String.join(".", g)), "signature");
        // 2. validUntil pushed ten years out (the Rx has none; an attacker adds one, or extends one)
        g = f.clone();
        g[4] = Long.toString(Long.parseLong(f[3]) + 315_360_000L);
        assertNoContent(verified(ph, String.join(".", g)), "signature");
        // 3. the prescription id swapped for another one (same clinic)
        String qe2 = visit(c);
        sign(c, qe2, new Object[]{"Qa amox", 1});
        String[] other = ((String) header(c, qe2).get("qrPayload")).split("\\.", -1);
        g = f.clone();
        g[2] = other[2];
        assertNoContent(verified(ph, String.join(".", g)), "signature");
        // 4. a signature from one QR on the head of another
        g = f.clone();
        g[7] = other[7];
        assertNoContent(verified(ph, String.join(".", g)), "signature");
        // 5. unknown kid (the kid is signed, so it is "signature", never a 500)
        g = f.clone();
        g[5] = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("nope".getBytes());
        ResponseEntity<Map> kid = verify(ph, Map.of("qrPayload", String.join(".", g)));
        assertThat(kid.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertNoContent(kid.getBody(), "signature");
        assertThat(scans(c)).isEqualTo(5);
        // the keyboard wedge appends CR/LF and spaces: still the same QR
        assertThat(verified(ph, "  " + qr + "\r\n")).containsEntry("status", "valid");
        assertThat(su().queryForObject("SELECT count(*) FROM rx_verifications WHERE tenant_id = ? AND result = 'invalid' AND prescription_id IS NOT NULL", Integer.class, c.t()))
                .as("a refused scan stores no prescription id").isZero();
    }

    @Test
    void qApi03_04_anotherClinicsQr_andAnUnknownPrescriptionSignedWithTheLiveKey() {
        Clinic a = clinic("in_house");
        Clinic b = clinic("in_house");
        item(a, "Qb drug", "OTC");
        String qe = visit(a);
        sign(a, qe, new Object[]{"Qb drug", 2});
        String qr = (String) header(a, qe).get("qrPayload");
        String phB = pharmacist(b);
        assertNoContent(verified(phB, qr), "other-tenant");
        // the same payload with the tenant field rewritten to B: the signature no longer matches, still no content
        String[] f = qr.split("\\.", -1);
        String[] bq = ((String) header(b, signedVisit(b)).get("qrPayload")).split("\\.", -1);
        f[1] = bq[1];
        assertNoContent(verified(phB, String.join(".", f)), "signature");
        // a properly signed QR (live key) for a prescription id that does not exist in B
        String kid = (String) ((List<Map<String, Object>>) keys.publicJwks().get("keys")).get(0).get("kid");
        String ghost = RxQr.encode(new RxQr.Claims(b.t(), UUID.randomUUID(), Instant.now().minusSeconds(60), null, kid, "REG-1"), keys);
        assertNoContent(verified(phB, ghost), "not-found");
        // a properly signed QR for A's prescription id but B's tenant: not-found in B, and A's data never shows
        String spoof = RxQr.encode(new RxQr.Claims(b.t(), UUID.fromString((String) header(a, qe).get("prescriptionId")), Instant.now().minusSeconds(60), null, kid, "REG-1"), keys);
        assertNoContent(verified(phB, spoof), "not-found");
        assertThat(su().queryForObject("SELECT count(*) FROM rx_verifications WHERE tenant_id = ?", Integer.class, a.t())).as("nothing audited in A").isZero();
        assertThat(scans(b)).isEqualTo(4);
    }

    private String signedVisit(Clinic b) {
        item(b, "Qb own", "OTC");
        String qe = visit(b);
        sign(b, qe, new Object[]{"Qb own", 1});
        return qe;
    }

    @Test
    void qApi05_07_expiryBoundary_andDispensedWinsOverExpired() {
        Clinic c = clinic("in_house");
        su().update("UPDATE pharmacy_schedule_rules SET rx_validity_days = 5 WHERE market = 'IN' AND schedule_class = 'IN_H'"); // test value only
        try {
            UUID drug = item(c, "Qe antibiotic", "IN_H");
            String qe = visit(c);
            List<String> rx = sign(c, qe, new Object[]{"Qe antibiotic", 4});
            Map<String, Object> h = header(c, qe);
            String qr = (String) h.get("qrPayload");
            String ph = pharmacist(c);
            // boundary: valid_until one minute in the future -> valid; one second in the past -> expired
            su().update("UPDATE pharmacy_worklist_entries SET valid_until = now() + interval '60 seconds' WHERE prescription_id = ?::uuid", h.get("prescriptionId"));
            assertThat(verified(ph, qr)).containsEntry("status", "valid");
            su().update("UPDATE pharmacy_worklist_entries SET valid_until = now() - interval '1 second' WHERE prescription_id = ?::uuid", h.get("prescriptionId"));
            Map<String, Object> v = verified(ph, qr);
            assertThat(v).containsEntry("status", "expired").containsEntry("reason", null).containsEntry("queueEntryId", qe);
            assertThat((Map<String, Object>) v.get("prescriber")).containsEntry("name", "Dr Qa Rao");
            assertThat((List<?>) v.get("remaining")).hasSize(1);
            // an expired Rx cannot be billed (PHL-012 422 rx-expired): bill while valid, then let it expire -> dispensed wins over expired
            su().update("UPDATE pharmacy_worklist_entries SET valid_until = now() + interval '1 hour' WHERE prescription_id = ?::uuid", h.get("prescriptionId"));
            toCheckout(c, qe);
            String code = su().queryForObject("SELECT code FROM charge_catalogue WHERE id = ?", String.class, drug);
            bill(c, qe, code, 4, rx.get(0));
            su().update("UPDATE pharmacy_worklist_entries SET valid_until = now() - interval '1 second' WHERE prescription_id = ?::uuid", h.get("prescriptionId"));
            Map<String, Object> d = verified(ph, qr);
            assertThat(d).containsEntry("status", "dispensed");
            assertThat((Map<String, Object>) d.get("dispensed")).containsKey("at").containsKey("where");
            assertThat((List<?>) d.get("remaining")).isEmpty();
        } finally {
            su().update("UPDATE pharmacy_schedule_rules SET rx_validity_days = NULL WHERE market = 'IN' AND schedule_class = 'IN_H'");
        }
    }

    @Test
    void qApi06_aPartlyDispensedRxIsValidAndListsOnlyWhatIsLeft() {
        Clinic c = clinic("in_house");
        UUID a = item(c, "Qp first", "OTC");
        item(c, "Qp second", "OTC");
        String qe = visit(c);
        List<String> rx = sign(c, qe, new Object[]{"Qp first", 10}, new Object[]{"Qp second", 3});
        String qr = (String) header(c, qe).get("qrPayload");
        String ph = pharmacist(c);
        toCheckout(c, qe);
        String code = su().queryForObject("SELECT code FROM charge_catalogue WHERE id = ?", String.class, a);
        bill(c, qe, code, 4, rx.get(0));
        Map<String, Object> v = verified(ph, qr);
        assertThat(v).containsEntry("status", "valid").containsEntry("dispensed", null);
        List<Map<String, Object>> rem = (List<Map<String, Object>>) v.get("remaining");
        assertThat(rem).hasSize(2);
        assertThat(((Number) rem.get(0).get("remaining")).doubleValue()).isEqualTo(6.0);
        assertThat(((Number) rem.get(0).get("qtyDispensed")).doubleValue()).isEqualTo(4.0);
        assertThat(((Number) rem.get(1).get("remaining")).doubleValue()).isEqualTo(3.0);
        assertThat(rem).extracting(m -> m.get("drug")).containsExactly("Qp first", "Qp second");
        // the visit is completed by its checkout; the rest is dispensed later as a counter sale against the same lines (PHL-012 flow), not re-tested here
    }

    @Test
    void qApi08_09_gates_andInputBounds() {
        Clinic c = clinic("in_house");
        item(c, "Qg drug", "OTC");
        String qe = visit(c);
        sign(c, qe, new Object[]{"Qg drug", 1});
        String qr = (String) header(c, qe).get("qrPayload");
        String viewOnly = staffToken(c, new ModuleGrant("pharmacy", true, false, false, false, false, false, false));
        String billingOnly = staffToken(c, new ModuleGrant("billing", true, true, true, false, false, false, false));
        String noDispense = staffToken(c, new ModuleGrant("pharmacy", true, true, false, false, false, false, false)); // view + create, no edit
        for (String t : List.of(viewOnly, billingOnly, noDispense)) {
            assertThat(verify(t, Map.of("qrPayload", qr)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exchange("/v1/pharmacy/rx-verifications/keys", HttpMethod.GET, authed(t), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(exchange("/v1/pharmacy/rx-verifications", HttpMethod.POST, jsonBody(Map.of("qrPayload", qr)), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(scans(c)).as("refused calls are not audited").isZero();

        String ph = pharmacist(c);
        assertThat(verify(ph, Map.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(verify(ph, Map.of("qrPayload", "   ")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        for (String bad : List.of("NRX1.a.b.c.d.e.f", "NRX1.......", "NRX1." + "x".repeat(1000), "NRX2" + qr.substring(4), qr + ".extra", "nrx1" + qr.substring(4))) {
            ResponseEntity<Map> r = verify(ph, Map.of("qrPayload", bad));
            assertThat(r.getStatusCode()).as("%s -> %s", bad.substring(0, Math.min(20, bad.length())), r.getBody()).isIn(HttpStatus.UNPROCESSABLE_CONTENT, HttpStatus.BAD_REQUEST); // >1000 chars is a 400 size rule
        }
        // a field count of 8 with an undecodable time: 422, not 500
        String[] f = qr.split("\\.", -1);
        f[3] = "99999999999999999999";
        assertThat(verify(ph, Map.of("qrPayload", String.join(".", f))).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(scans(c)).as("unreadable scans are not audited").isZero();
        f = qr.split("\\.", -1);
        f[3] = "-5"; // parses (a time before 1970), so it is a readable payload with a bad signature: audited as invalid
        ResponseEntity<Map> neg = verify(ph, Map.of("qrPayload", String.join(".", f)));
        assertThat(neg.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertNoContent(neg.getBody(), "signature");
        // keys: the kid of the QR is there, no private part
        List<Map<String, Object>> jwks = (List<Map<String, Object>>) exchange("/v1/pharmacy/rx-verifications/keys", HttpMethod.GET, authed(ph), Map.class).getBody().get("keys");
        String kid = new String(java.util.Base64.getUrlDecoder().decode(qr.split("\\.", -1)[5]));
        assertThat(jwks).extracting(k -> k.get("kid")).contains(kid);
        assertThat(jwks).allSatisfy(k -> assertThat(k).doesNotContainKey("d"));
    }

    @Test
    void qApi10_11_hybridClinicStillVerifies_andADraftHasNoQr() {
        Clinic c = clinic("hybrid");
        item(c, "Qh drug", "OTC");
        String qe = visit(c);
        exchange("/v1/clinical/prescriptions/" + qe, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("items", List.of(Map.of("drugName", "Qh drug", "dosage", "1", "frequency", "OD", "quantity", 1, "quantityUnit", "tablets")))), Map.class);
        ResponseEntity<Map> draft = exchange("/v1/clinical/prescriptions/" + qe + "/print-header", HttpMethod.GET, authed(c.token()), Map.class);
        assertThat(draft.getStatusCode()).as("no QR for an unsigned prescription").isEqualTo(HttpStatus.CONFLICT);
        assertThat(exchange("/v1/clinical/prescriptions/" + qe + "/sign", HttpMethod.POST, authed(c.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        for (int i = 0; i < 5; i++) dispatcher.poll();
        String qr = (String) header(c, qe).get("qrPayload");
        Map<String, Object> v = verified(pharmacist(c), qr);
        assertThat(v).containsEntry("status", "valid").containsEntry("worklistEntryId", null);
        assertThat((Map<String, Object>) v.get("prescriber")).containsEntry("name", "Dr Qa Rao");
        // the print-header response itself carries no secret: payload has no names or drugs
        assertThat(qr).doesNotContain("Rao").doesNotContain("Qh");
        // a pharmacist of the same clinic without clinical:view cannot read the print header (the QR is for the printout)
        assertThat(exchange("/v1/clinical/prescriptions/" + qe + "/print-header", HttpMethod.GET, authed(pharmacist(c)), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
