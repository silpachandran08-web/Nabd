package com.nabd.hms.billing;

import com.nabd.hms.support.ClinicianFixture;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** FIN-015 per-doctor fees + configurable follow-up window; FIN-016 emergency suggestion + price change log. */
class Fin015Fin016PricingApiTest extends S10QaBillingSupport {

    private static final JdbcTemplate SU = new JdbcTemplate(superuserDataSource());

    private Env env2() {
        Env e = env();
        ClinicianFixture.license(e.tenant().id(), e.owner().id());
        return e;
    }

    private UUID head(Env e, String code) {
        return inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT id FROM charge_catalogue WHERE code = ?", UUID.class, code));
    }

    private UUID doctor(Env e) {
        var s = seedStaff(e.tenant(), seedRole(e.tenant().id(), "Doc" + UUID.randomUUID(), false, fullGrant("queue"), fullGrant("patients")), "dr" + UUID.randomUUID() + "@a.com", phone(), false);
        ClinicianFixture.license(e.tenant().id(), s.id());
        return s.id();
    }

    private ResponseEntity<Map> putFees(Env e, String token, UUID head, Object... doctorAmounts) {
        List<Map<String, Object>> fees = new java.util.ArrayList<>();
        for (int i = 0; i < doctorAmounts.length; i += 2) {
            Map<String, Object> f = new HashMap<>();
            f.put("doctorId", doctorAmounts[i].toString());
            f.put("amount", doctorAmounts[i + 1]);
            fees.add(f);
        }
        return exchange("/v1/setup/charges/" + head + "/doctor-fees", HttpMethod.PUT, authedJsonBody(token, Map.of("fees", fees)), Map.class);
    }

    private String checkInWith(Env e, String patientId, UUID doctorId) {
        return (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(e.token(), Map.of(
                "patientId", patientId, "doctorId", doctorId.toString())), Map.class).getBody().get("id");
    }

    private Map<?, ?> consLine(ResponseEntity<Map> r) {
        return (Map<?, ?>) ((List<?>) r.getBody().get("lineItems")).get(0);
    }

    private Map<?, ?> suggested(Env e, String entry) {
        return (Map<?, ?>) ((List<?>) exchange("/v1/billing/checkout/" + entry, HttpMethod.GET, authed(e.token()), Map.class).getBody().get("suggestedLines")).get(0);
    }

    private void backdate(String entry, int days) {
        SU.update("UPDATE queue_entries SET queue_date = CURRENT_DATE - ?, token_number = ? WHERE id = ?::uuid", days, 8000 + (int) (Math.random() * 1000), entry);
    }

    /** A completed full-price visit of the patient with the doctor, {@code days} days ago. */
    private String pastVisit(Env e, String patient, UUID doctor, int days) {
        String id = checkInWith(e, patient, doctor);
        move(e, id, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        checkout(e.token(), id, List.of(line("CONS", 1)));
        backdate(id, days);
        return id;
    }

    // FIN-015 AC1
    @Test
    void perDoctorFeeIsBilledAndABlankOverrideFallsBackToTheBaseAmount() {
        Env e = env2();
        UUID drB = doctor(e);
        UUID cons = head(e, "CONS"); // base 400
        ResponseEntity<Map> put = putFees(e, e.token(), cons, e.owner().id(), 800, drB, null);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) put.getBody().get("fees")).hasSize(1);

        String a = checkInWith(e, patient(e.token()), e.owner().id());
        assertThat(num(suggested(e, a).get("unitPrice"))).isEqualTo(800.0);
        move(e, a, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        assertThat(num(consLine(checkout(e.token(), a, List.of(line("CONS", 1)))).get("unitPrice"))).isEqualTo(800.0);

        String b = checkInWith(e, patient(e.token()), drB);
        move(e, b, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        assertThat(num(consLine(checkout(e.token(), b, List.of(line("CONS", 1)))).get("unitPrice"))).isEqualTo(400.0);

        // clearing Dr A's override goes back to the base amount and is logged
        putFees(e, e.token(), cons, e.owner().id(), null);
        assertThat(count(e, "SELECT count(*) FROM charge_doctor_fees WHERE charge_id = ?", cons)).isZero();
        assertThat(count(e, "SELECT count(*) FROM charge_price_changes WHERE charge_id = ? AND field = 'doctor_fee'", cons)).isEqualTo(2);
    }

    @Test
    void doctorFeesNeedAConsultationHeadAKnownDoctorAndSetupEdit() {
        Env e = env2();
        ResponseEntity<Map> proc = putFees(e, e.token(), head(e, "XRAY"), e.owner().id(), 900);
        assertThat(proc.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(proc.getBody().get("type")).asString().contains("NOT_CONSULTATION_CATEGORY");
        assertThat(putFees(e, e.token(), head(e, "CONS"), UUID.randomUUID(), 900).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(putFees(e, e.token(), head(e, "CONS"), e.owner().id(), -1).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(putFees(e, e.token(), UUID.randomUUID(), e.owner().id(), 5).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        String billingOnly = tokenFor(e, "Cashier", true, true, true, false, true);
        assertThat(putFees(e, billingOnly, head(e, "CONS"), e.owner().id(), 5).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/setup/charges/changes", HttpMethod.GET, authed(billingOnly), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // FIN-015 AC2 + AC3 + anchor rule
    @Test
    void followUpDependsOnTheSameDoctorTheWindowAndTheLastFullPriceVisit() {
        Env e = env2();
        UUID drB = doctor(e);
        String patient = patient(e.token());
        pastVisit(e, patient, e.owner().id(), 6);
        String back = checkInWith(e, patient, e.owner().id());
        Map<?, ?> s = suggested(e, back);
        assertThat(s.get("tier")).isEqualTo("follow_up");
        assertThat(s.get("reason")).isEqualTo("Follow-up · last visit 6 days ago");
        assertThat(num(s.get("unitPrice"))).isEqualTo(150.0);

        String other = checkInWith(e, patient, drB); // same patient, different doctor
        assertThat(suggested(e, other).get("tier")).isEqualTo("normal");

        // 8 days: normal (window 7)
        SU.update("UPDATE queue_entries SET queue_date = CURRENT_DATE - 8 WHERE tenant_id = ? AND patient_id = ?::uuid AND status = 'completed'", e.tenant().id(), patient);
        assertThat(suggested(e, back).get("tier")).isEqualTo("normal");
    }

    @Test
    void aFollowUpVisitDoesNotRestartTheWindow() {
        Env e = env2();
        String patient = patient(e.token());
        pastVisit(e, patient, e.owner().id(), 8); // full price, 8 days ago
        String fu = checkInWith(e, patient, e.owner().id());
        // the full-price visit is 8 days old: outside the window, so this one is billed normal; make the earlier one 5 days old to get a follow-up
        SU.update("UPDATE queue_entries SET queue_date = CURRENT_DATE - 5 WHERE tenant_id = ? AND patient_id = ?::uuid AND status = 'completed'", e.tenant().id(), patient);
        move(e, fu, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        assertThat(consLine(checkout(e.token(), fu, List.of(line("CONS", 1)))).get("tier")).isEqualTo("follow_up");
        // now: full price visit 8 days ago, follow-up 3 days ago -> counted from the full-price visit, so normal
        SU.update("UPDATE queue_entries SET queue_date = CURRENT_DATE - 8 WHERE tenant_id = ? AND patient_id = ?::uuid AND status = 'completed' AND id <> ?::uuid", e.tenant().id(), patient, fu);
        SU.update("UPDATE queue_entries SET queue_date = CURRENT_DATE - 3, token_number = 8999 WHERE id = ?::uuid", fu);
        String next = checkInWith(e, patient, e.owner().id());
        assertThat(suggested(e, next).get("tier")).isEqualTo("normal");
    }

    // FIN-015 AC4
    @Test
    void proceduresNeverGetFollowUpOrDoctorFeeAndAnUnofferedTierIsRefused() {
        Env e = env2();
        putFees(e, e.token(), head(e, "CONS"), e.owner().id(), 800);
        String patient = patient(e.token());
        pastVisit(e, patient, e.owner().id(), 2);
        String entry = checkInWith(e, patient, e.owner().id());
        move(e, entry, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        Map<String, Object> xray = line("XRAY", 1);
        xray.put("tier", "emergency");
        assertThat(checkout(e.token(), entry, List.of(xray)).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<Map> ok = checkout(e.token(), entry, List.of(line("XRAY", 1)));
        assertThat(num(consLine(ok).get("unitPrice"))).isEqualTo(1200.0);
        assertThat(consLine(ok).get("tier")).isEqualTo("normal");
    }

    // FIN-015 AC5
    @Test
    void changingTheWindowAppliesToTheNextCheckoutIsAuditedAndRangeChecked() {
        Env e = env2();
        String patient = patient(e.token());
        pastVisit(e, patient, e.owner().id(), 9);
        String entry = checkInWith(e, patient, e.owner().id());
        assertThat(suggested(e, entry).get("tier")).isEqualTo("normal");
        ResponseEntity<Map> put = exchange("/v1/setup/policies/follow_up_window_days", HttpMethod.PUT, authedJsonBody(e.token(), Map.of("value", 10)), Map.class);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put.getBody().get("value")).isEqualTo("10");
        assertThat(suggested(e, entry).get("tier")).isEqualTo("follow_up");
        assertThat(count(e, "SELECT count(*) FROM audit_log WHERE action = 'setup.follow_up_window_changed'")).isEqualTo(1);
        for (int bad : new int[]{0, 91, -3}) {
            assertThat(exchange("/v1/setup/policies/follow_up_window_days", HttpMethod.PUT, authedJsonBody(e.token(), Map.of("value", bad)), Map.class).getStatusCode())
                    .as("value " + bad).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        assertThat(exchange("/v1/setup/policies/follow_up_window_days", HttpMethod.PUT, authedJsonBody(e.token(), Map.of("value", 90)), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        // the generic PATCH is range checked too
        assertThat(exchange("/v1/setup/policies/follow_up_window_days", HttpMethod.PATCH, authedJsonBody(e.token(), Map.of("value", "abc")), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    // FIN-016 AC1 + AC2
    @Test
    void urgentCheckInPreselectsEmergencyButNothingIsChargedUntilTheReceptionistPicks() {
        Env e = env2();
        SU.update("UPDATE charge_catalogue SET emergency_amount = 1000 WHERE tenant_id = ? AND code = 'CONS'", e.tenant().id());
        String cashier = tokenFor(e, "Cashier", true, true, true, false, true); // no billing.discount
        String entry = atCheckout(e);
        assertThat(suggested(e, entry).get("tier")).as("not urgent").isEqualTo("normal");
        SU.update("UPDATE queue_entries SET priority = true, priority_reason = 'chest pain' WHERE id = ?::uuid", entry);
        Map<?, ?> s = suggested(e, entry);
        assertThat(s.get("tier")).isEqualTo("emergency");
        assertThat(s.get("reason")).isEqualTo("Urgent check-in");
        assertThat(num(s.get("unitPrice"))).isEqualTo(1000.0);
        assertThat(num(s.get("emergencyAmount"))).isEqualTo(1000.0);
        assertThat(num(s.get("normalAmount"))).isEqualTo(400.0);

        // no tier sent: the server does not charge emergency by itself
        // switching to normal needs no override permission and is recorded on the line
        Map<String, Object> normal = line("CONS", 1);
        normal.put("tier", "normal");
        ResponseEntity<Map> r = checkout(cashier, entry, List.of(normal));
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(num(consLine(r).get("unitPrice"))).isEqualTo(400.0);
        assertThat(consLine(r).get("tier")).isEqualTo("normal");

        String second = atCheckout(e);
        SU.update("UPDATE queue_entries SET priority = true, priority_reason = 'chest pain' WHERE id = ?::uuid", second);
        assertThat(num(consLine(checkout(cashier, second, List.of(line("CONS", 1)))).get("unitPrice"))).as("urgent alone does not charge emergency").isEqualTo(400.0);

        String third = atCheckout(e);
        Map<String, Object> emergency = line("CONS", 1);
        emergency.put("tier", "emergency");
        ResponseEntity<Map> r3 = checkout(cashier, third, List.of(emergency));
        assertThat(num(consLine(r3).get("unitPrice"))).isEqualTo(1000.0);
        assertThat(consLine(r3).get("tier")).isEqualTo("emergency");
        assertThat(count(e, "SELECT count(*) FROM audit_log WHERE action = 'billing.price_overridden'")).as("a tier pick is not an override").isZero();
    }

    @Test
    void emergencyWithoutAnEmergencyPriceIsRefused() {
        Env e = env2();
        String entry = atCheckout(e);
        Map<String, Object> emergency = line("CONS", 1);
        emergency.put("tier", "emergency");
        ResponseEntity<Map> r = checkout(e.token(), entry, List.of(emergency));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("type")).asString().contains("tier-not-available");
    }

    private Map<String, Object> chargeBody(String code, String name, String category, double base, Double followUp, Double emergency, boolean active) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code); b.put("name", name); b.put("category", category); b.put("baseAmount", base);
        b.put("followUpAmount", followUp); b.put("emergencyAmount", emergency); b.put("doctorOverride", false); b.put("active", active);
        b.put("effectiveFrom", "2026-01-01"); b.put("displayOrder", 0);
        return b;
    }

    // FIN-016 AC3 + AC4
    @Test
    void everyPriceEditIsLoggedIssuedInvoicesKeepTheirPriceAndTheLogFiltersAndExports() {
        Env e = env2();
        UUID cons = head(e, "CONS");
        String earlier = invoiceOf(e, "CONS"); // issued at 400
        ResponseEntity<Map> patch = exchange("/v1/setup/charges/" + cons, HttpMethod.PATCH,
                authedJsonBody(e.token(), chargeBody("CONS", "Cat CONS", "Consultation", 600, 150.0, 900.0, true)), Map.class);
        assertThat(patch.getStatusCode()).isEqualTo(HttpStatus.OK);
        // a PATCH that changes nothing writes nothing
        exchange("/v1/setup/charges/" + cons, HttpMethod.PATCH, authedJsonBody(e.token(), chargeBody("CONS", "Cat CONS", "Consultation", 600, 150.0, 900.0, true)), Map.class);
        exchange("/v1/setup/charges/" + head(e, "XRAY"), HttpMethod.PATCH, authedJsonBody(e.token(), chargeBody("XRAY", "Cat XRAY", "Procedure", 1200, null, null, false)), Map.class);

        ResponseEntity<Map> all = exchange("/v1/setup/charges/changes", HttpMethod.GET, authed(e.token()), Map.class);
        List<Map<String, Object>> items = (List<Map<String, Object>>) all.getBody().get("items");
        assertThat(items).hasSize(3);
        Map<String, Object> base = items.stream().filter(i -> "base_amount".equals(i.get("field"))).findFirst().orElseThrow();
        assertThat(base.get("oldValue")).isEqualTo("400.00");
        assertThat(base.get("newValue")).isEqualTo("600.00");
        assertThat(base.get("chargeId")).isEqualTo(cons.toString());
        assertThat(base.get("actor")).asString().isNotBlank().isNotEqualTo("Unknown");
        assertThat(base.get("at")).isNotNull();
        assertThat(items).extracting(i -> i.get("field")).containsExactlyInAnyOrder("base_amount", "emergency_amount", "active");

        // issued invoice still shows 400; the next bill uses 600
        Map<?, ?> inv = getInvoice(e.token(), earlier).getBody();
        assertThat(num(((Map<?, ?>) ((List<?>) inv.get("lineItems")).get(0)).get("unitPrice"))).isEqualTo(400.0);
        assertThat(num(consLine(checkout(e.token(), atCheckout(e), List.of(line("CONS", 1)))).get("unitPrice"))).isEqualTo(600.0);

        // filters: head, field, date
        assertThat(((List<?>) exchange("/v1/setup/charges/changes?chargeId=" + cons, HttpMethod.GET, authed(e.token()), Map.class).getBody().get("items"))).hasSize(2);
        assertThat(((List<?>) exchange("/v1/setup/charges/changes?field=active", HttpMethod.GET, authed(e.token()), Map.class).getBody().get("items"))).hasSize(1);
        String today = SU.queryForObject("SELECT (now() AT TIME ZONE 'UTC')::date::text", String.class);
        assertThat(((List<?>) exchange("/v1/setup/charges/changes?from=2020-01-01&to=2020-01-02", HttpMethod.GET, authed(e.token()), Map.class).getBody().get("items"))).isEmpty();
        assertThat(((List<?>) exchange("/v1/setup/charges/changes?from=" + today + "&to=" + today, HttpMethod.GET, authed(e.token()), Map.class).getBody().get("items"))).hasSize(3);
        assertThat(exchange("/v1/setup/charges/changes?field=nope", HttpMethod.GET, authed(e.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // paging
        Map<?, ?> p1 = exchange("/v1/setup/charges/changes?limit=2", HttpMethod.GET, authed(e.token()), Map.class).getBody();
        assertThat((List<?>) p1.get("items")).hasSize(2);
        assertThat(p1.get("nextCursor")).isNotNull();
        Map<?, ?> p2 = exchange("/v1/setup/charges/changes?limit=2&cursor=" + p1.get("nextCursor"), HttpMethod.GET, authed(e.token()), Map.class).getBody();
        assertThat((List<?>) p2.get("items")).hasSize(1);
        assertThat(p2.get("nextCursor")).isNull();

        // CSV
        ResponseEntity<String> csv = exchange("/v1/setup/charges/changes/export?field=base_amount", HttpMethod.GET, authed(e.token()), String.class);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csv.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(csv.getBody()).startsWith("at,charge,field,doctor,old_value,new_value,actor").contains("\"base_amount\"", "\"400.00\"", "\"600.00\"").doesNotContain("emergency_amount");
    }

    @Test
    void theLogAndTheFeesAreTenantIsolated() {
        Env a = env2();
        Env b = env2();
        exchange("/v1/setup/charges/" + head(a, "CONS"), HttpMethod.PATCH, authedJsonBody(a.token(), chargeBody("CONS", "Cat CONS", "Consultation", 777, 150.0, null, true)), Map.class);
        putFees(a, a.token(), head(a, "CONS"), a.owner().id(), 900);
        assertThat(((List<?>) exchange("/v1/setup/charges/changes", HttpMethod.GET, authed(b.token()), Map.class).getBody().get("items"))).isEmpty();
        assertThat(count(b, "SELECT count(*) FROM charge_price_changes")).isZero();
        assertThat(count(b, "SELECT count(*) FROM charge_doctor_fees")).isZero();
        assertThat(count(a, "SELECT count(*) FROM charge_doctor_fees")).isEqualTo(1);
        assertThat(exchange("/v1/setup/charges/" + head(a, "CONS") + "/doctor-fees", HttpMethod.GET, authed(b.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(putFees(b, b.token(), head(a, "CONS"), b.owner().id(), 1).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
