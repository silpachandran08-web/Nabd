package com.nabd.hms.billing.gateway;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nabd.hms.shared.security.Audience;
import com.nabd.hms.shared.security.TokenType;
import com.nabd.hms.support.S16E16FakeServicesBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** FIN-019 against a local fake Razorpay server and an in-memory tenant secret store (the production writer is not built, see GatewaySecrets). */
class Fin019GatewayApiTest extends S16E16FakeServicesBase {

    static final List<String> SERVER_SEEN = RAZORPAY_SEEN;

    @Autowired JwtEncoder encoder;

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void capture() {
        SERVER_SEEN.clear();
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
    }

    @AfterEach
    void release() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
    }

    private record Clinic(SeededTenant tenant, SeededStaff owner, String token) {
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "own" + t.slug() + "@a.com", "+9195" + (10000000 + (int) (Math.random() * 89999999)), false);
        return new Clinic(t, owner, loginAndGetAccessToken(owner));
    }

    private String stepUp(String access) {
        {
            Map<String, Object> c = objectMapper.readValue(Base64.getUrlDecoder().decode(access.split("\\.")[1]), Map.class);
            Instant now = Instant.now();
            JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now).expiresAt(now.plusSeconds(300))
                    .subject((String) c.get("sub")).claim("purpose", "step_up").claim("sid", c.get("sid")).claim("tenantId", c.get("tenantId"));
            TokenType.STEP_UP.stamp(b, Audience.STEP_UP);
            return encoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
        }
    }

    private ResponseEntity<Map> connect(Clinic c, String provider, String mode, String keyId, String secret, boolean withStepUp) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("provider", provider, "mode", mode));
        if (keyId != null) body.put("keys", Map.of("keyId", keyId, "keySecret", secret));
        return exchange("/v1/billing/gateway-accounts", HttpMethod.POST,
                authedJsonBodyWithHeader(c.token(), "X-Step-Up-Token", withStepUp ? stepUp(c.token()) : null, body), Map.class);
    }

    private ResponseEntity<Map> connectOk(Clinic c) {
        return connect(c, "razorpay", "keys", KEY_ID, KEY_SECRET, true);
    }

    private int rows(Clinic c, String where) {
        return inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM payment_gateway_accounts WHERE " + where, Integer.class));
    }

    // AC1 (connect and verify; webhook registration and the auto-refund are Needs external action)
    // AC2
    @Test
    void connectVerifiesTheKeysStoresThemOnlyInTheSecretStoreAndNeverLogsOrReturnsThem() {
        Clinic c = clinic();
        ResponseEntity<Map> r = connectOk(c);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("status")).isEqualTo("verified");
        assertThat(r.getBody().get("provider")).isEqualTo("razorpay");
        assertThat(SERVER_SEEN).anyMatch(s -> s.startsWith("GET /v1/payments"));
        String vaultKey = c.tenant().id() + "/gateway-razorpay";
        assertThat(VAULT.get(vaultKey)).contains(KEY_SECRET).contains(KEY_ID);
        // nowhere else: not in the response, not in any column of the gateway table, the audit log, or the logs
        assertThat(r.getBody().toString()).doesNotContain(KEY_SECRET).doesNotContain(KEY_ID);
        String dump = new JdbcTemplate(superuserDataSource()).queryForObject(
                "SELECT string_agg(g::text, ' ') || ' ' || (SELECT coalesce(string_agg(a::text, ' '), '') FROM audit_log a WHERE a.tenant_id = ?) FROM payment_gateway_accounts g WHERE g.tenant_id = ?",
                String.class, c.tenant().id(), c.tenant().id());
        assertThat(dump).contains("gateway-razorpay").doesNotContain(KEY_SECRET).doesNotContain(KEY_ID);
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'billing.gateway_connected'", Integer.class))).isEqualTo(1);
        Map<?, ?> cur = exchange("/v1/billing/gateway-accounts/current", HttpMethod.GET, authed(c.token()), Map.class).getBody();
        assertThat(cur.get("connected")).isEqualTo(true);
        assertThat(cur.toString()).doesNotContain(KEY_SECRET);

        // test payment: a 1.00 hosted link in the market currency; the log never shows the key either
        String id = (String) r.getBody().get("accountId");
        ResponseEntity<Map> t = exchange("/v1/billing/gateway-accounts/" + id + "/test-payment", HttpMethod.POST, authedJsonBody(c.token(), Map.of()), Map.class);
        assertThat(t.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(t.getBody().get("testId")).isNotNull();
        assertThat(t.getBody().get("paymentLinkUrl")).isEqualTo("https://rzp.io/i/test1");
        assertThat(SERVER_SEEN).anyMatch(s -> s.startsWith("POST /v1/payment_links") && s.contains("\"amount\":100") && s.contains("\"currency\":\"INR\""));
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).noneMatch(m -> m.contains(KEY_SECRET) || m.contains(KEY_ID));
    }

    // AC3
    @Test
    void aWrongKeyFailsWithAClearMessageAndNothingIsSaved() {
        Clinic c = clinic();
        ResponseEntity<Map> r = connect(c, "razorpay", "keys", KEY_ID, "not-the-secret", true);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("type")).asString().contains("VERIFICATION_FAILED");
        assertThat(r.getBody().get("detail")).asString().contains("nothing was saved");
        assertThat(rows(c, "true")).isZero();
        assertThat(VAULT.keySet()).noneMatch(k -> k.startsWith(c.tenant().id().toString()));
        assertThat(connect(c, "razorpay", "keys", "", "", true).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void oneAccountPerClinicAndReconnectingAfterDisconnectWorks() {
        Clinic c = clinic();
        String id = (String) connectOk(c).getBody().get("accountId");
        ResponseEntity<Map> second = connectOk(c);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("type")).asString().contains("ALREADY_CONNECTED");

        // AC4 (backend half): disconnected = the status endpoint says so, the keys are gone
        ResponseEntity<Void> del = exchange("/v1/billing/gateway-accounts/" + id, HttpMethod.DELETE,
                authedJsonBodyWithHeader(c.token(), "X-Step-Up-Token", stepUp(c.token()), Map.of()), Void.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(VAULT).doesNotContainKey(c.tenant().id() + "/gateway-razorpay");
        assertThat(exchange("/v1/billing/gateway-accounts/current", HttpMethod.GET, authed(c.token()), Map.class).getBody().get("connected")).isEqualTo(false);
        assertThat(rows(c, "status = 'disabled' AND disabled_at IS NOT NULL")).isEqualTo(1);
        assertThat(exchange("/v1/billing/gateway-accounts/" + id, HttpMethod.DELETE,
                authedJsonBodyWithHeader(c.token(), "X-Step-Up-Token", stepUp(c.token()), Map.of()), Void.class).getStatusCode()).as("repeat is a no-op").isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange("/v1/billing/gateway-accounts/" + id + "/test-payment", HttpMethod.POST, authedJsonBody(c.token(), Map.of()), Map.class).getStatusCode())
                .as("a disabled account cannot test").isEqualTo(HttpStatus.CONFLICT);
        assertThat(connectOk(c).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(rows(c, "status <> 'disabled'")).isEqualTo(1);
    }

    @Test
    void connectAndDisconnectNeedStepUpAndSetupEdit() {
        Clinic c = clinic();
        assertThat(connect(c, "razorpay", "keys", KEY_ID, KEY_SECRET, false).getStatusCode().is4xxClientError()).isTrue();
        assertThat(rows(c, "true")).isZero();
        String id = (String) connectOk(c).getBody().get("accountId");
        assertThat(exchange("/v1/billing/gateway-accounts/" + id, HttpMethod.DELETE, authedJsonBody(c.token(), Map.of()), Void.class).getStatusCode().is4xxClientError()).isTrue();
        assertThat(rows(c, "status = 'verified'")).isEqualTo(1);

        String billingOnly = loginAndGetAccessToken(seedStaff(c.tenant(), seedRole(c.tenant().id(), "Cashier", false, new com.nabd.hms.common.ModuleGrant("billing", true, true, true, false, false, false, false)),
                "cash" + UUID.randomUUID() + "@a.com", "+9194" + (10000000 + (int) (Math.random() * 89999999)), false));
        assertThat(exchange("/v1/billing/gateway-accounts/current", HttpMethod.GET, authed(billingOnly), Map.class).getBody().get("connected")).isEqualTo(true);
        assertThat(exchange("/v1/billing/gateway-accounts", HttpMethod.POST, authedJsonBody(billingOnly, Map.of("provider", "razorpay", "mode", "keys")), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/billing/gateway-accounts/" + id + "/test-payment", HttpMethod.POST, authedJsonBody(billingOnly, Map.of()), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unbuiltProvidersAndOauthAreRefusedAndATestNeedsAVerifiedAccount() {
        Clinic c = clinic();
        for (String p : new String[]{"tap", "hyperpay", "network_intl", "bogus"}) {
            ResponseEntity<Map> r = connect(c, p, "keys", KEY_ID, KEY_SECRET, true);
            assertThat(r.getStatusCode()).as(p).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        assertThat(connect(c, "razorpay", "oauth", null, null, true).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(rows(c, "true")).isZero();
        UUID pending = UUID.randomUUID();
        new JdbcTemplate(superuserDataSource()).update("INSERT INTO payment_gateway_accounts (id, tenant_id, provider, secret_ref, status) VALUES (?,?, 'razorpay', 'gateway-razorpay', 'pending')", pending, c.tenant().id());
        ResponseEntity<Map> t = exchange("/v1/billing/gateway-accounts/" + pending + "/test-payment", HttpMethod.POST, authedJsonBody(c.token(), Map.of()), Map.class);
        assertThat(t.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(t.getBody().get("type")).asString().contains("NOT_VERIFIED");
    }

    @Test
    void accountsAreTenantIsolated() {
        Clinic a = clinic();
        Clinic b = clinic();
        String id = (String) connectOk(a).getBody().get("accountId");
        assertThat(exchange("/v1/billing/gateway-accounts/current", HttpMethod.GET, authed(b.token()), Map.class).getBody().get("connected")).isEqualTo(false);
        assertThat(exchange("/v1/billing/gateway-accounts/" + id + "/test-payment", HttpMethod.POST, authedJsonBody(b.token(), Map.of()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/billing/gateway-accounts/" + id, HttpMethod.DELETE,
                authedJsonBodyWithHeader(b.token(), "X-Step-Up-Token", stepUp(b.token()), Map.of()), Void.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rows(b, "true")).isZero();
        assertThat(rows(a, "true")).isEqualTo(1);
        assertThat(connectOk(b).getStatusCode()).as("another clinic can connect its own").isEqualTo(HttpStatus.CREATED);
    }
}
