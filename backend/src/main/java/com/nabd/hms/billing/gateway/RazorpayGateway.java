package com.nabd.hms.billing.gateway;

import com.nabd.hms.shared.integration.ResilientHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FIN-019: Razorpay (India). Key-entry mode: the clinic's key id and secret are checked with one authenticated read,
 * then used for hosted payment links and refunds (amounts in paise). Every call goes through {@link ResilientHttpClient}
 * (timeouts, circuit breaker, refused inside a DB transaction). Webhook registration and partner OAuth need Razorpay
 * partner credentials: Needs external action (not built here). shortcut: response shapes follow the public Razorpay API
 * docs and a local fake server in the test, not a live sandbox account.
 */
@Component
class RazorpayGateway implements PaymentGateway {

    private final RestClient http;

    RazorpayGateway(ResilientHttpClient integration, @Value("${nabd.payments.razorpay.base-url:https://api.razorpay.com/v1}") String baseUrl) {
        this.http = integration.builder("razorpay").baseUrl(baseUrl).build();
    }

    @Override
    public String provider() {
        return "razorpay";
    }

    @Override
    public String currency() {
        return "INR";
    }

    @Override
    public void verify(Credentials c) {
        call(c, () -> http.get().uri("/payments?count=1").header(HttpHeaders.AUTHORIZATION, basic(c)).retrieve().toBodilessEntity());
    }

    @Override
    @SuppressWarnings("unchecked")
    public PaymentLink createLink(Credentials c, long amountMinor, String currency, String reference, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountMinor);
        body.put("currency", currency);
        body.put("reference_id", reference);
        body.put("description", description);
        Map<String, Object> r = call(c, () -> http.post().uri("/payment_links").header(HttpHeaders.AUTHORIZATION, basic(c))
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class));
        if (r == null || r.get("id") == null || r.get("short_url") == null) throw new GatewayException(GatewayException.UNAVAILABLE);
        return new PaymentLink(r.get("id").toString(), r.get("short_url").toString());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Refund refund(Credentials c, String paymentId, long amountMinor) {
        Map<String, Object> r = call(c, () -> http.post().uri("/payments/{id}/refund", paymentId).header(HttpHeaders.AUTHORIZATION, basic(c))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("amount", amountMinor)).retrieve().body(Map.class));
        if (r == null || r.get("id") == null) throw new GatewayException(GatewayException.UNAVAILABLE);
        return new Refund(r.get("id").toString(), String.valueOf(r.get("status")));
    }

    /** 401/403 = the keys are wrong; anything else (5xx, timeout, open breaker) = the gateway is unavailable. */
    private static <T> T call(Credentials c, java.util.function.Supplier<T> request) {
        if (c == null || c.keyId() == null || c.keyId().isBlank() || c.keySecret() == null || c.keySecret().isBlank()) {
            throw new GatewayException(GatewayException.INVALID_CREDENTIALS);
        }
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            int s = e.getStatusCode().value();
            throw new GatewayException(s == 401 || s == 403 ? GatewayException.INVALID_CREDENTIALS : GatewayException.UNAVAILABLE);
        } catch (GatewayException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new GatewayException(GatewayException.UNAVAILABLE);
        }
    }

    private static String basic(Credentials c) {
        return "Basic " + Base64.getEncoder().encodeToString((c.keyId() + ":" + c.keySecret()).getBytes(StandardCharsets.UTF_8));
    }
}
