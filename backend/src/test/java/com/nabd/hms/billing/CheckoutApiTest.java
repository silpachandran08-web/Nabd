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

class CheckoutApiTest extends ApiTestBase {

    private String registerPatient(String token, String name, String phone) {
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", "1990-01-01", "gender", "male")), Map.class);
        return (String) resp.getBody().get("id");
    }

    private String checkIn(String token, String patientId, UUID doctorId) {
        ResponseEntity<Map> resp = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", doctorId.toString())), Map.class);
        return (String) resp.getBody().get("id");
    }

    private void moveTo(String token, String queueEntryId, String... statuses) {
        for (String s : statuses) {
            exchange("/v1/queue/" + queueEntryId + "/status", HttpMethod.PATCH,
                    authedJsonBody(token, Map.of("status", s)), Map.class);
        }
    }

    /** Checks in a patient and walks the queue all the way to checkout_pending, ready to bill. */
    private String checkoutPendingEntry(String token, UUID doctorId, String patientName, String phone) {
        String patientId = registerPatient(token, patientName, phone);
        String queueEntryId = checkIn(token, patientId, doctorId);
        moveTo(token, queueEntryId, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        return queueEntryId;
    }

    @Test
    void checkoutComputesTotalsMatchingLineItemsAndCompletesTheVisit() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "recep1@a.com", "+919700000001", false);
        String token = loginAndGetAccessToken(staff);
        String queueEntryId = checkoutPendingEntry(token, staff.id(), "C1", "+919999930001");

        // 2 x 500.00 @ 18% tax = 1000 subtotal, 180 tax, no discount -> total 1180 (whole rupee already)
        ResponseEntity<Map> resp = exchange("/v1/billing/checkout/" + queueEntryId, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("chargeCode", "CONSULT", "chargeName", "Consultation",
                        "category", "Consultation", "quantity", 2, "unitPrice", 500.00, "taxRatePercent", 18.00)),
                "discount", 0)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) resp.getBody().get("subtotal")).doubleValue()).isEqualTo(1000.00);
        assertThat(((Number) resp.getBody().get("tax")).doubleValue()).isEqualTo(180.00);
        assertThat(((Number) resp.getBody().get("total")).doubleValue()).isEqualTo(1180.00);
        assertThat(((Number) resp.getBody().get("roundOff")).doubleValue()).isEqualTo(0.0);
        assertThat(resp.getBody().get("status")).isEqualTo("unpaid");
        assertThat(resp.getBody().get("invoiceNumber")).asString().startsWith("INV-");

        ResponseEntity<Map> queue = exchange("/v1/queue/" + queueEntryId + "/status", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("status", "completed")), Map.class);
        // already completed by checkout — a second attempt at the same transition is illegal, proving it happened
        assertThat(queue.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void checkoutAppliesRoundOffToTheNearestWholeCurrencyUnit() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "recep2@a.com", "+919700000002", false);
        String token = loginAndGetAccessToken(staff);
        String queueEntryId = checkoutPendingEntry(token, staff.id(), "C2", "+919999930002");

        // 333.33 subtotal, 0% tax, no discount -> raw total 333.33, rounds to 333, round-off -0.33
        ResponseEntity<Map> resp = exchange("/v1/billing/checkout/" + queueEntryId, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("chargeCode", "X", "chargeName", "X", "category", "Service",
                        "quantity", 1, "unitPrice", 333.33, "taxRatePercent", 0)),
                "discount", 0)), Map.class);

        assertThat(((Number) resp.getBody().get("total")).doubleValue()).isEqualTo(333.00);
        assertThat(((Number) resp.getBody().get("roundOff")).doubleValue()).isEqualTo(-0.33);
    }

    @Test
    void checkoutBeforeCheckoutPendingIsRejected() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "recep3@a.com", "+919700000003", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "C3", "+919999930003");
        String queueEntryId = checkIn(token, patientId, staff.id()); // still "checked_in", not checkout_pending

        ResponseEntity<Map> resp = exchange("/v1/billing/checkout/" + queueEntryId, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("chargeCode", "X", "chargeName", "X", "category", "Service",
                        "quantity", 1, "unitPrice", 100, "taxRatePercent", 0)))), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("type")).asString().contains("not-checkout-pending");
    }

    @Test
    void checkingOutAgainAfterCompletionIsRejected() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "recep4@a.com", "+919700000004", false);
        String token = loginAndGetAccessToken(staff);
        String queueEntryId = checkoutPendingEntry(token, staff.id(), "C4", "+919999930004");
        Map<String, Object> body = Map.of("lineItems", List.of(Map.of("chargeCode", "X", "chargeName", "X",
                "category", "Service", "quantity", 1, "unitPrice", 100, "taxRatePercent", 0)));

        exchange("/v1/billing/checkout/" + queueEntryId, HttpMethod.POST, authedJsonBody(token, body), Map.class);
        // checkout already moved the queue entry to 'completed', so a second attempt is rejected by
        // the status guard before it can ever reach the invoice-exists guard — that one only matters
        // for two concurrent submits racing while the entry is still checkout_pending.
        ResponseEntity<Map> second = exchange("/v1/billing/checkout/" + queueEntryId, HttpMethod.POST, authedJsonBody(token, body), Map.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(second.getBody().get("type")).asString().contains("not-checkout-pending");
    }

    @Test
    void discountLargerThanSubtotalIsRejected() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "recep5@a.com", "+919700000005", false);
        String token = loginAndGetAccessToken(staff);
        String queueEntryId = checkoutPendingEntry(token, staff.id(), "C5", "+919999930005");

        ResponseEntity<Map> resp = exchange("/v1/billing/checkout/" + queueEntryId, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("chargeCode", "X", "chargeName", "X", "category", "Service",
                        "quantity", 1, "unitPrice", 100, "taxRatePercent", 0)),
                "discount", 500)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("type")).asString().contains("discount-exceeds-subtotal");
    }

    @Test
    void paymentsAccumulateToPartialThenPaid() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "recep6@a.com", "+919700000006", false);
        String token = loginAndGetAccessToken(staff);
        String queueEntryId = checkoutPendingEntry(token, staff.id(), "C6", "+919999930006");
        ResponseEntity<Map> invoice = exchange("/v1/billing/checkout/" + queueEntryId, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("chargeCode", "X", "chargeName", "X", "category", "Service",
                        "quantity", 1, "unitPrice", 1000, "taxRatePercent", 0)))), Map.class);
        String invoiceId = (String) invoice.getBody().get("id");

        ResponseEntity<Map> partial = exchange("/v1/billing/invoices/" + invoiceId + "/payments", HttpMethod.POST,
                authedJsonBody(token, Map.of("method", "cash", "amount", 400)), Map.class);
        assertThat(partial.getBody().get("status")).isEqualTo("partial");
        assertThat(((Number) partial.getBody().get("balanceDue")).doubleValue()).isEqualTo(600.00);

        ResponseEntity<Map> paidUp = exchange("/v1/billing/invoices/" + invoiceId + "/payments", HttpMethod.POST,
                authedJsonBody(token, Map.of("method", "upi", "amount", 600)), Map.class);
        assertThat(paidUp.getBody().get("status")).isEqualTo("paid");

        ResponseEntity<Map> blocked = exchange("/v1/billing/invoices/" + invoiceId + "/payments", HttpMethod.POST,
                authedJsonBody(token, Map.of("method", "cash", "amount", 1)), Map.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void followUpEligibleWhenSameDoctorHasARecentCompletedVisit() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "recep7@a.com", "+919700000007", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "C7", "+919999930007");

        String firstEntry = checkIn(token, patientId, staff.id());
        moveTo(token, firstEntry, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        exchange("/v1/billing/checkout/" + firstEntry, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("chargeCode", "X", "chargeName", "X", "category", "Service",
                        "quantity", 1, "unitPrice", 100, "taxRatePercent", 0)))), Map.class);

        String secondEntry = checkIn(token, patientId, staff.id());
        ResponseEntity<Map> context = exchange("/v1/billing/checkout/" + secondEntry, HttpMethod.GET, authed(token), Map.class);

        assertThat(context.getBody().get("followUpEligible")).isEqualTo(true);
    }

    @Test
    void otcCheckoutCreatesInvoiceWithNoPatientOrQueueEntry() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "recep9@a.com", "+919700000009", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> charges = exchange("/v1/billing/otc-charges", HttpMethod.GET, authed(token), Map.class);
        assertThat(charges.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(charges.getBody().get("currency")).isEqualTo("INR");

        // NB-186: a counter sale — no patient record, no queue token, just an invoice.
        ResponseEntity<Map> resp = exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("chargeCode", "X", "chargeName", "X", "category", "Service",
                        "quantity", 1, "unitPrice", 100.00, "taxRatePercent", 0)),
                "discount", 0)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("queueEntryId")).isNull();
        assertThat(resp.getBody().get("patientId")).isNull();
        assertThat(resp.getBody().get("doctorId")).isNull();
        assertThat(resp.getBody().get("patientName")).isEqualTo("Walk-in customer");
        assertThat(((Number) resp.getBody().get("total")).doubleValue()).isEqualTo(100.00);
        assertThat(resp.getBody().get("invoiceNumber")).asString().startsWith("INV-");

        String invoiceId = (String) resp.getBody().get("id");
        ResponseEntity<Map> fetched = exchange("/v1/billing/invoices/" + invoiceId, HttpMethod.GET, authed(token), Map.class);
        assertThat(fetched.getBody().get("patientId")).isNull();
    }

    @Test
    void otcCheckoutOfAPharmacyItemDecrementsStock() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "recep10@a.com", "+919700000010", false);
        String token = loginAndGetAccessToken(staff);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid")), Map.class);
        ResponseEntity<Map> item = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Paracetamol 500mg", "isRx", false, "hsnCode", "3004", "price", 20.00,
                "taxRatePercent", 12.00, "stockQty", 10)), Map.class);
        String code = (String) item.getBody().get("code");

        exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("chargeCode", code, "chargeName", "Paracetamol 500mg", "category", "Pharmacy",
                        "quantity", 3, "unitPrice", 20.00, "taxRatePercent", 12.00)))), Map.class);

        ResponseEntity<List> afterSale = exchange("/v1/pharmacy/items", HttpMethod.GET, authed(token), List.class);
        assertThat(((Map<?, ?>) afterSale.getBody().get(0)).get("stockQty")).isEqualTo(7);
    }

    @Test
    void checkoutContextSurfacesTheSignedPrescriptionSoBillingCanSeeWhatToDispense() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "recep11@a.com", "+919700000011", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "PRx", "+919999941011");
        String queueEntryId = checkIn(token, patientId, staff.id());
        exchange("/v1/clinical/prescriptions/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Amoxicillin", "dosage", "500mg", "frequency", "TDS")))), Map.class);
        exchange("/v1/clinical/prescriptions/" + queueEntryId + "/sign", HttpMethod.POST, authed(token), Map.class);
        moveTo(token, queueEntryId, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");

        ResponseEntity<Map> ctx = exchange("/v1/billing/checkout/" + queueEntryId, HttpMethod.GET, authed(token), Map.class);

        List<Map> prescribed = (List<Map>) ctx.getBody().get("prescribedItems");
        assertThat(prescribed).hasSize(1);
        assertThat(prescribed.get(0).get("drugName")).isEqualTo("Amoxicillin");
    }

    @Test
    void roleWithoutBillingGrantIsForbidden() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "QueueOnly", false, fullGrant("queue"), fullGrant("patients"));
        SeededStaff staff = seedStaff(tenant, roleId, "norole1@a.com", "+919700000008", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/billing/checkout/" + UUID.randomUUID(), HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
