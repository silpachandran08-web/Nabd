package com.nabd.hms.billing;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * FIN-020: what the payment-intent flow needs from a gateway adapter (Razorpay, Tap, HyperPay). FIN-019 (gateway connect)
 * supplies the production adapters and the per-tenant credentials; with no connected adapter the desk falls back to
 * recording payments (409 GATEWAY_NOT_CONNECTED). Adapters are called inside the tenant transaction; they hold no card data.
 */
public interface PaymentGatewayPort {

    /** Path segment of the webhook URL and value of payment_intents.provider, e.g. "razorpay". */
    String provider();

    /** True when the bound tenant has a verified account at this gateway. */
    boolean connected(UUID tenantId);

    /** The tenant's webhook signing secret (HMAC-SHA256 of the raw body, hex), empty when not connected. */
    Optional<byte[]> webhookSecret(UUID tenantId);

    /** Creates the hosted order/QR for the exact amount; the gateway must refuse payment after {@code expiresAt}. */
    Order createOrder(UUID tenantId, UUID intentId, long amountMinor, String currency, String kind, Instant expiresAt);

    /** Asks the gateway for the state of an order (reconciliation of lost webhooks). */
    OrderState fetchOrder(UUID tenantId, String providerOrderId);

    /** Reads a verified webhook body. */
    Event parse(byte[] body);

    record Order(String providerOrderId, String url, String qrPayload) {
    }

    record OrderState(boolean paid, boolean failed, String gatewayRef) {
    }

    /** type is one of payment.captured, order.paid, payment.failed; others are stored and ignored. */
    record Event(String eventId, String type, String providerOrderId, String gatewayRef, Long amountMinor) {
    }
}
