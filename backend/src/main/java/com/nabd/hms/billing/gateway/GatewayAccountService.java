package com.nabd.hms.billing.gateway;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.billing.gateway.GatewayAccountStore.Account;
import com.nabd.hms.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FIN-019: connect, test and disconnect the clinic's own gateway account. Deliberately NOT transactional: the gateway is
 * called between short store transactions (ExternalCallGuard refuses HTTP inside one). Order on connect: refuse a second
 * account, verify the keys with the gateway, write them to the tenant secret store, then insert the pointer row, so a wrong
 * key saves nothing (AC3) and the keys exist only in the secret store (AC2).
 */
@Service
public class GatewayAccountService {

    private static final Logger log = LoggerFactory.getLogger(GatewayAccountService.class);

    public record Keys(String keyId, String keySecret) {
        @Override
        public String toString() {
            return "Keys[***]";
        }
    }

    public record ConnectRequest(String provider, String mode, Keys keys) {
    }

    public record AccountView(UUID accountId, String provider, String status, java.time.Instant connectedAt, java.time.Instant lastTestAt, String oauthUrl) {
    }

    public record Current(boolean connected, AccountView account) {
    }

    public record TestRun(UUID testId, String paymentLinkUrl) {
    }

    private final GatewayAccountStore store;
    private final List<PaymentGateway> gateways;
    private final ObjectProvider<GatewaySecrets> secrets;
    private final ObjectMapper json;

    GatewayAccountService(GatewayAccountStore store, List<PaymentGateway> gateways, ObjectProvider<GatewaySecrets> secrets, ObjectMapper json) {
        this.store = store;
        this.gateways = gateways;
        this.secrets = secrets;
        this.json = json;
    }

    public AccountView connect(UUID staffId, String ip, ConnectRequest req) {
        if (req == null || req.provider() == null || req.mode() == null) throw bad("Send a provider and a mode (keys).");
        PaymentGateway gw = gateways.stream().filter(g -> g.provider().equals(req.provider())).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "provider-unavailable", "Gateway not available", "That payment gateway is not available."));
        if (!gw.enabled()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "provider-unavailable", "Gateway not available yet", "That payment gateway is not available yet.");
        }
        if ("oauth".equals(req.mode())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "OAUTH_NOT_AVAILABLE", "Partner connect not available", "Connect with your keys instead.");
        }
        if (!"keys".equals(req.mode()) || req.keys() == null || blank(req.keys().keyId()) || blank(req.keys().keySecret())) throw bad("Enter your key id and key secret.");
        GatewaySecrets vault = secrets.getIfAvailable();
        if (vault == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "secret-store-unavailable", "Secure storage unavailable", "Your keys cannot be stored securely right now, so nothing was saved.");
        }
        UUID tenantId = store.tenantWithoutLiveAccount();
        try {
            gw.verify(new PaymentGateway.Credentials(req.keys().keyId(), req.keys().keySecret()));
        } catch (GatewayException e) {
            if (GatewayException.INVALID_CREDENTIALS.equals(e.reason())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "VERIFICATION_FAILED", "Keys not accepted", "The gateway did not accept those keys. Check them and try again; nothing was saved.");
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "gateway-unavailable", "Gateway not reachable", "The gateway could not be reached. Try again in a minute; nothing was saved.");
        }
        String name = "gateway-" + gw.provider();
        try {
            vault.put(tenantId, name, json.writeValueAsString(Map.of("keyId", req.keys().keyId(), "keySecret", req.keys().keySecret())));
        } catch (JacksonException e) {
            throw new IllegalStateException("could not encode the gateway keys");
        }
        try {
            return view(store.insert(gw.provider(), name, staffId, ip));
        } catch (RuntimeException e) {
            vault.delete(tenantId, name); // the pointer row was not saved: do not leave orphan keys behind
            throw e;
        }
    }

    public Current current() {
        return store.current().map(a -> new Current(true, view(a))).orElse(new Current(false, null));
    }

    /** Creates a 1-unit hosted payment link in the market currency for the owner to pay with a real card. The auto-refund needs the payment webhook (not built). */
    public TestRun testPayment(UUID id) {
        Account a = store.get(id);
        if (!"verified".equals(a.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_VERIFIED", "Gateway not verified", "Connect and verify the gateway before running a test payment.");
        }
        PaymentGateway gw = gateways.stream().filter(g -> g.provider().equals(a.provider())).findFirst().orElseThrow();
        PaymentGateway.Credentials keys = store.credentials(a);
        UUID testId = UUID.randomUUID();
        try {
            var link = gw.createLink(keys, 100, gw.currency(), "nabd-test-" + testId, "Nabd test payment");
            store.markTest(id);
            return new TestRun(testId, link.url());
        } catch (GatewayException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "gateway-unavailable", "Gateway not reachable", "The test payment could not be created. Try again in a minute.");
        }
    }

    public void disconnect(UUID id, UUID staffId, String ip) {
        UUID tenantId = store.tenant();
        String secretName = store.disable(id, staffId, ip);
        GatewaySecrets vault = secrets.getIfAvailable();
        if (secretName != null && vault != null) {
            try {
                vault.delete(tenantId, secretName);
            } catch (RuntimeException e) {
                log.error("gateway account {} disconnected but its secret could not be deleted", id); // the row is disabled; an operator removes the secret
            }
        }
    }

    private static AccountView view(Account a) {
        return new AccountView(a.id(), a.provider(), a.status(), a.connectedAt(), a.lastTestAt(), null);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static ApiException bad(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", detail);
    }
}
