package com.nabd.hms.billing;

import com.nabd.hms.billing.document.S15BillingTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** FIN-020: payment intents (QR / link), HMAC webhooks with dedupe, lost-webhook poller, caregiver payer, refusals. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Fin020PaymentIntentApiTest extends S15BillingTestBase {

    @Autowired PaymentIntentService intents;

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private ResponseEntity<Map> createIntent(Env e, String token, String invoice, Map<String, Object> body) {
        return exchange("/v1/billing/invoices/" + invoice + "/payment-intents", HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private ResponseEntity<Map> webhook(Env e, String body, String signature) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (signature != null) h.set("X-Razorpay-Signature", signature);
        return exchange("/v1/billing/gateway-webhooks/fake/" + e.tenant().id(), HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }

    private ResponseEntity<Map> signedWebhook(Env e, byte[] secret, String body) {
        return webhook(e, body, FakePaymentGateway.sign(secret, body));
    }

    private Map status(Env e, String intentId) {
        return exchange("/v1/billing/payment-intents/" + intentId, HttpMethod.GET, authed(e.ownerToken()), Map.class).getBody();
    }

    private long count(String sql, Object... args) {
        return su().queryForObject(sql, Long.class, args);
    }

    private static String orderOf(String intentId) {
        return "order_" + intentId.replace("-", "");
    }

    @Test
    void qrForTheExactBalanceThenSignedWebhookTurnsTheInvoicePaidWithGatewayColumns() {
        Env e = env();
        byte[] secret = FakePaymentGateway.connect(e.tenant().id());
        String inv = invoice(e, patient(e), "CONS");

        long t0 = System.nanoTime();
        ResponseEntity<Map> r = createIntent(e, e.ownerToken(), inv, Map.of("kind", "qr"));
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(2000);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = (String) r.getBody().get("intentId");
        assertThat((String) r.getBody().get("qrPayload")).contains("am=40000"); // 400.00 INR in minor units
        assertThat(r.getBody().get("expiresAt")).isNotNull();
        assertThat(status(e, id).get("status")).isEqualTo("created");

        assertThat(signedWebhook(e, secret, FakePaymentGateway.event("evt1", "payment.captured", orderOf(id), 40000)).getStatusCode()).isEqualTo(HttpStatus.OK);

        Map s = status(e, id);
        assertThat(s.get("status")).isEqualTo("paid");
        assertThat(s.get("gatewayRef")).isEqualTo("pay_evt1");
        assertThat(s.get("paidAt")).isNotNull();
        Map invoice = exchange("/v1/billing/invoices/" + inv, HttpMethod.GET, authed(e.ownerToken()), Map.class).getBody();
        assertThat(invoice.get("status")).isEqualTo("paid");
        Map row = su().queryForMap("SELECT method, gateway, gateway_ref, payer_kind, payment_intent_id FROM invoice_payments WHERE invoice_id = ?::uuid", inv);
        assertThat(row.get("method")).isEqualTo("upi");
        assertThat(row.get("gateway")).isEqualTo("fake");
        assertThat(row.get("payer_kind")).isEqualTo("patient");
        assertThat(row.get("payment_intent_id").toString()).isEqualTo(id);
    }

    @Test
    void theSameWebhookDeliveredThreeTimesMakesExactlyOnePayment() {
        Env e = env();
        byte[] secret = FakePaymentGateway.connect(e.tenant().id());
        String inv = invoice(e, patient(e), "CONS");
        String id = (String) createIntent(e, e.ownerToken(), inv, Map.of("kind", "qr")).getBody().get("intentId");
        String body = FakePaymentGateway.event("evtR", "payment.captured", orderOf(id), 40000);
        for (int i = 0; i < 3; i++) {
            assertThat(signedWebhook(e, secret, body).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(count("SELECT count(*) FROM invoice_payments WHERE invoice_id = ?::uuid", inv)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM gateway_webhook_events WHERE tenant_id = ?::uuid", e.tenant().id().toString())).isEqualTo(1);
        // a different event for the same order (order.paid after payment.captured) must not pay twice either
        assertThat(signedWebhook(e, secret, FakePaymentGateway.event("evtR2", "order.paid", orderOf(id), 40000)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(count("SELECT count(*) FROM invoice_payments WHERE invoice_id = ?::uuid", inv)).isEqualTo(1);
    }

    @Test
    void badOrMissingSignatureIs401AndNothingIsStored() {
        Env e = env();
        byte[] secret = FakePaymentGateway.connect(e.tenant().id());
        String inv = invoice(e, patient(e), "CONS");
        String id = (String) createIntent(e, e.ownerToken(), inv, Map.of("kind", "qr")).getBody().get("intentId");
        String body = FakePaymentGateway.event("evtBad", "payment.captured", orderOf(id), 40000);
        assertThat(webhook(e, body, FakePaymentGateway.sign(new byte[32], body)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(webhook(e, body, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // a tenant that never connected: same 401, no hint that it exists
        Env other = env();
        String ob = FakePaymentGateway.event("evtOther", "payment.captured", "order_x", 100);
        assertThat(exchange("/v1/billing/gateway-webhooks/fake/" + other.tenant().id(), HttpMethod.POST,
                new HttpEntity<>(ob, jsonHeaders(FakePaymentGateway.sign(secret, ob))), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/v1/billing/gateway-webhooks/fake/not-a-uuid", HttpMethod.POST,
                new HttpEntity<>(ob, jsonHeaders("x")), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(count("SELECT count(*) FROM gateway_webhook_events WHERE event_id IN ('evtOther')")).isZero();
        assertThat(count("SELECT count(*) FROM gateway_webhook_events WHERE event_id = 'evtBad'")).isZero();
    }

    private static HttpHeaders jsonHeaders(String sig) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Razorpay-Signature", sig);
        return h;
    }

    @Test
    void aLostWebhookIsSettledByThePollerOnceAfter15Minutes() {
        Env e = env();
        FakePaymentGateway.connect(e.tenant().id());
        String inv = invoice(e, patient(e), "CONS");
        String id = (String) createIntent(e, e.ownerToken(), inv, Map.of("kind", "qr")).getBody().get("intentId");
        FakePaymentGateway.payAtGateway(orderOf(id));

        assertThat(intents.reconcile(e.tenant().id())).isZero(); // younger than 15 minutes: left alone
        assertThat(status(e, id).get("status")).isEqualTo("created");

        inTenantTx(e.tenant().id(), () -> jdbc.update("UPDATE payment_intents SET created_at = ? WHERE id = ?::uuid",
                Timestamp.from(Instant.now().minus(20, ChronoUnit.MINUTES)), id));
        assertThat(intents.reconcile(e.tenant().id())).isEqualTo(1);
        assertThat(intents.reconcile(e.tenant().id())).isZero();
        assertThat(status(e, id).get("status")).isEqualTo("paid");
        assertThat(count("SELECT count(*) FROM invoice_payments WHERE invoice_id = ?::uuid", inv)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM invoice_payments WHERE invoice_id = ?::uuid AND method = 'upi'", inv)).isEqualTo(1);
    }

    @Test
    void aLinkToALinkedCaregiverRecordsTheCaregiverAsPayer() {
        Env e = env();
        byte[] secret = FakePaymentGateway.connect(e.tenant().id());
        inTenantTx(e.tenant().id(), () -> jdbc.update("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status)
                VALUES (?, 'payment_link', ?, 'en', 'UTILITY', 'Clinic', 'Pay {{2}} to {{1}}: {{3}}', 3, 'approved')
                """, e.tenant().id(), "m_" + UUID.randomUUID().toString().replace("-", "")));
        String child = patient(e), parent = patient(e), stranger = patient(e);
        String inv = invoice(e, child, "CONS");
        su().update("UPDATE patients SET guardian_id = ?::uuid WHERE id = ?::uuid", parent, child);
        inTenantTx(e.tenant().id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?, ?::uuid, 'messaging')", e.tenant().id(), parent));

        Map<String, Object> notCaregiver = Map.of("kind", "link", "recipient", "caregiver", "caregiverId", stranger);
        ResponseEntity<Map> refused = createIntent(e, e.ownerToken(), inv, notCaregiver);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().get("code")).isEqualTo("NOT_A_CAREGIVER");
        assertThat(createIntent(e, e.ownerToken(), inv, Map.of("kind", "link", "recipient", "caregiver")).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        ResponseEntity<Map> ok = createIntent(e, e.ownerToken(), inv, Map.of("kind", "link", "recipient", "caregiver", "caregiverId", parent));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = (String) ok.getBody().get("intentId");
        assertThat((String) ok.getBody().get("url")).startsWith("https://");
        Instant exp = Instant.parse((String) ok.getBody().get("expiresAt"));
        assertThat(exp).isBetween(Instant.now().plus(23, ChronoUnit.HOURS), Instant.now().plus(25, ChronoUnit.HOURS));
        assertThat(count("SELECT count(*) FROM notification_requests WHERE tenant_id = ?::uuid AND subject_id = ?::uuid", e.tenant().id().toString(), id)).isEqualTo(1);

        signedWebhook(e, secret, FakePaymentGateway.event("evtC", "payment.captured", orderOf(id), 40000));
        Map row = su().queryForMap("SELECT payer_kind, payer_ref FROM invoice_payments WHERE invoice_id = ?::uuid", inv);
        assertThat(row.get("payer_kind")).isEqualTo("caregiver");
        assertThat(row.get("payer_ref").toString()).isEqualTo(parent);
    }

    @Test
    void partialAmountIsAllowedMoreThanTheBalanceIsNot() {
        Env e = env();
        byte[] secret = FakePaymentGateway.connect(e.tenant().id());
        String inv = invoice(e, patient(e), "CONS");
        ResponseEntity<Map> big = createIntent(e, e.ownerToken(), inv, Map.of("kind", "qr", "amount", 400.01));
        assertThat(big.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(big.getBody().get("code")).isEqualTo("AMOUNT_EXCEEDS_BALANCE");
        assertThat(createIntent(e, e.ownerToken(), inv, Map.of("kind", "qr", "amount", 0)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        String id = (String) createIntent(e, e.ownerToken(), inv, Map.of("kind", "qr", "amount", 150.5)).getBody().get("intentId");
        signedWebhook(e, secret, FakePaymentGateway.event("evtP", "payment.captured", orderOf(id), 15050));
        Map invoice = exchange("/v1/billing/invoices/" + inv, HttpMethod.GET, authed(e.ownerToken()), Map.class).getBody();
        assertThat(invoice.get("status")).isEqualTo("partial");

        // a payment of another amount than quoted is parked for a person, not applied
        String id2 = (String) createIntent(e, e.ownerToken(), inv, Map.of("kind", "qr", "amount", 100)).getBody().get("intentId");
        signedWebhook(e, secret, FakePaymentGateway.event("evtM", "payment.captured", orderOf(id2), 99));
        assertThat(status(e, id2).get("status")).isEqualTo("created");
        assertThat(count("SELECT count(*) FROM gateway_webhook_events WHERE event_id = 'evtM' AND error = 'amount-mismatch' AND processed_at IS NULL")).isEqualTo(1);
    }

    @Test
    void noGatewayIs409ViewOnlyCannotCreateAnExpiredIntentShowsExpiredAndTenantsAreIsolated() {
        Env e = env();
        String inv = invoice(e, patient(e), "CONS");
        ResponseEntity<Map> none = createIntent(e, e.ownerToken(), inv, Map.of("kind", "qr"));
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(none.getBody().get("code")).isEqualTo("GATEWAY_NOT_CONNECTED");

        FakePaymentGateway.connect(e.tenant().id());
        String viewOnly = billingToken(e, "viewer", true, false, false);
        assertThat(createIntent(e, viewOnly, inv, Map.of("kind", "qr")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(createIntent(e, e.ownerToken(), inv, Map.of("kind", "cheque")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        String id = (String) createIntent(e, e.ownerToken(), inv, Map.of("kind", "qr")).getBody().get("intentId");
        Env other = env();
        FakePaymentGateway.connect(other.tenant().id());
        assertThat(exchange("/v1/billing/payment-intents/" + id, HttpMethod.GET, authed(other.ownerToken()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(createIntent(other, other.ownerToken(), inv, Map.of("kind", "qr")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        inTenantTx(e.tenant().id(), () -> jdbc.update("UPDATE payment_intents SET expires_at = now() - interval '1 minute' WHERE id = ?::uuid", id));
        assertThat(status(e, id).get("status")).isEqualTo("expired");
    }
}
