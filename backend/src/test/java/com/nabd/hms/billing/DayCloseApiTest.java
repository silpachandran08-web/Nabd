package com.nabd.hms.billing;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** E15 Billing & Day Close: the day's figures, what blocks a close, the cash-count rule, the lock, and reopening. */
class DayCloseApiTest extends ApiTestBase {

    private String visitAtCheckout(String token, UUID doctor, String name, String phone, String dob) {
        String patient = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", dob, "gender", "female")), Map.class).getBody().get("id");
        String q = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patient, "doctorId", doctor.toString())), Map.class).getBody().get("id");
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending")) {
            exchange("/v1/queue/" + q + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", s)), Map.class);
        }
        return q;
    }

    /** Consultation 800 at 0% + cosmetic 200 at 18% (tax 36) → total 1036. */
    private String bill(String token, String queueEntryId) {
        ResponseEntity<Map> inv = exchange("/v1/billing/checkout/" + queueEntryId, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(
                        Map.of("chargeCode", "CONS", "chargeName", "Consultation", "category", "Consultation", "quantity", 1, "unitPrice", 800, "taxRatePercent", 0),
                        Map.of("chargeCode", "COS", "chargeName", "Cosmetic", "category", "Service", "quantity", 1, "unitPrice", 200, "taxRatePercent", 18)))),
                Map.class);
        assertThat(inv.getStatusCode().is2xxSuccessful()).isTrue();
        return (String) inv.getBody().get("id");
    }

    private ResponseEntity<Map> pay(String token, String invoiceId, String method, int amount) {
        return exchange("/v1/billing/invoices/" + invoiceId + "/payments", HttpMethod.POST,
                authedJsonBody(token, Map.of("method", method, "amount", amount)), Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void summaryCloseLockAndReopen() {
        SeededTenant tenant = seedTenant();
        SeededStaff owner = seedStaff(tenant, seedFullAccessRole(tenant.id()), "dc1@a.com", "+919600000001", false);
        String token = loginAndGetAccessToken(owner);

        String a = visitAtCheckout(token, owner.id(), "Anita Rao", "+919999951001", "1985-03-02");
        String invA = bill(token, a);
        assertThat(pay(token, invA, "cash", 600).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(pay(token, invA, "card", 300).getStatusCode().is2xxSuccessful()).isTrue(); // split, 136 still owed
        String b = visitAtCheckout(token, owner.id(), "Rahul Nair", "+919999951002", "1972-07-19");   // ended, no bill

        Map<String, Object> s = exchange("/v1/billing/day-close", HttpMethod.GET, authed(token), Map.class).getBody();
        assertThat(s).containsEntry("today", true).containsEntry("currency", "INR").containsEntry("taxLabel", "GST")
                .containsEntry("pendingCheckouts", 1).containsEntry("splitPaymentBills", 1);
        Map<String, Object> totals = (Map<String, Object>) s.get("totals");
        assertThat(((Number) totals.get("billed")).doubleValue()).isEqualTo(1036.0);
        assertThat(((Number) totals.get("collected")).doubleValue()).isEqualTo(900.0);
        assertThat(((Number) totals.get("tax")).doubleValue()).isEqualTo(36.0);
        List<Map<String, Object>> methods = (List<Map<String, Object>>) s.get("methods");
        assertThat(methods).extracting(m -> m.get("method")).containsExactly("cash", "card", "upi", "other");
        assertThat(((Number) methods.get(0).get("amount")).doubleValue()).isEqualTo(600.0);
        List<Map<String, Object>> bands = (List<Map<String, Object>>) s.get("taxBands");
        assertThat(bands.stream().mapToDouble(x -> ((Number) x.get("tax")).doubleValue()).sum()).isEqualTo(36.0); // reconciles
        assertThat(((Number) ((Map<String, Object>) s.get("outstanding")).get("amount")).doubleValue()).isEqualTo(136.0);
        List<Map<String, Object>> unbilled = (List<Map<String, Object>>) s.get("unbilled");
        assertThat(unbilled).singleElement().satisfies(u -> assertThat(u).containsEntry("patientName", "Rahul Nair")
                .containsEntry("queueEntryId", b));
        assertThat(s.get("close")).isNull();

        // blocked while B has no bill
        ResponseEntity<Map> blocked = exchange("/v1/billing/day-close", HttpMethod.POST, authedJsonBody(token, Map.of("countedCash", 600)), Map.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blocked.getBody().get("detail")).asString().contains("1 completed consultation still has no bill");

        String invB = bill(token, b);
        assertThat(pay(token, invB, "cash", 1036).getStatusCode().is2xxSuccessful()).isTrue(); // expected cash now 1636

        // off by 400 (> ₹200 tolerance) needs a note; the outstanding 136 on A doesn't block
        ResponseEntity<Map> noNote = exchange("/v1/billing/day-close", HttpMethod.POST, authedJsonBody(token, Map.of("countedCash", 1236)), Map.class);
        assertThat(noNote.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> closed = exchange("/v1/billing/day-close", HttpMethod.POST,
                authedJsonBody(token, Map.of("countedCash", 1236, "note", "₹400 float taken to bank")), Map.class);
        assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> close = (Map<String, Object>) closed.getBody().get("close");
        assertThat(close).containsEntry("status", "closed").containsEntry("closedByName", "Test Staff");
        assertThat(((Number) close.get("expectedCash")).doubleValue()).isEqualTo(1636.0);
        assertThat(((Number) close.get("variance")).doubleValue()).isEqualTo(-400.0);

        // locked: no payments, no second close
        ResponseEntity<Map> lockedPay = pay(token, invA, "upi", 136);
        assertThat(lockedPay.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(lockedPay.getBody().get("type")).asString().endsWith("day-closed");
        assertThat(exchange("/v1/billing/day-close", HttpMethod.POST, authedJsonBody(token, Map.of("countedCash", 1636)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // reopen with a reason → billing works again; both actions are audited
        ResponseEntity<Map> reopened = exchange("/v1/billing/day-close/reopen", HttpMethod.POST,
                authedJsonBody(token, Map.of("reason", "Missing bill found afterwards")), Map.class);
        assertThat(reopened.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Map<String, Object>) reopened.getBody().get("close")).containsEntry("status", "reopened")
                .containsEntry("reopenReason", "Missing bill found afterwards");
        assertThat(pay(token, invA, "upi", 136).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(inTenantTx(tenant.id(), () -> jdbc.queryForList(
                "SELECT action FROM audit_log WHERE tenant_id = ? AND entity_type = 'day_close' ORDER BY id", String.class, tenant.id())))
                .containsExactly("day_close.close", "day_close.reopen");
    }

    @Test
    void closingNeedsBillingApprove() {
        SeededTenant tenant = seedTenant();
        UUID cashier = seedRole(tenant.id(), "Cashier", false,
                new com.nabd.hms.common.ModuleGrant("billing", true, true, true, false, false, false, false));
        String token = loginAndGetAccessToken(seedStaff(tenant, cashier, "cash@a.com", "+919600000011", false));
        assertThat(exchange("/v1/billing/day-close", HttpMethod.GET, authed(token), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/billing/day-close", HttpMethod.POST, authedJsonBody(token, Map.of("countedCash", 0)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
