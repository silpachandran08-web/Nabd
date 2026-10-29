package com.nabd.hms.billing.gateway;

import java.time.LocalDate;
import java.util.List;

/**
 * FIN-019: the clinic's own payment gateway behind one port, so adapters are swappable per market (FRD SYS-08, DEP-03).
 * The clinic is the merchant of record; Nabd never holds funds and never sees card data (PCI SAQ-A: hosted links only).
 * Only the operations a story has needed are implemented by an adapter; the rest default to {@link GatewayException#unsupported}.
 */
public interface PaymentGateway {

    /** Key material the clinic pasted. toString never shows the secret. */
    record Credentials(String keyId, String keySecret) {
        @Override
        public String toString() {
            return "Credentials[keyId=" + (keyId == null ? null : "***") + "]";
        }
    }

    record PaymentLink(String id, String url) {
    }

    record Refund(String id, String status) {
    }

    record Settlement(String id, long amountMinor, String currency, LocalDate settledOn) {
    }

    String provider();

    /** The currency of the market this adapter serves; the 1-unit test payment is made in it. */
    String currency();

    /** An adapter that is built but not yet switched on for any clinic (Tap/HyperPay wait for FIN-024). */
    default boolean enabled() {
        return true;
    }

    /** Cheap authenticated call; throws {@link GatewayException} (invalid_credentials / unavailable) when the keys do not work. */
    void verify(Credentials credentials);

    default PaymentLink createLink(Credentials credentials, long amountMinor, String currency, String reference, String description) {
        throw GatewayException.unsupported(provider());
    }

    default PaymentLink createQr(Credentials credentials, long amountMinor, String currency, String reference) {
        throw GatewayException.unsupported(provider());
    }

    default Refund refund(Credentials credentials, String paymentId, long amountMinor) {
        throw GatewayException.unsupported(provider());
    }

    default List<Settlement> fetchSettlements(Credentials credentials, LocalDate day) {
        throw GatewayException.unsupported(provider());
    }

    default String createMandate(Credentials credentials, String customerRef, long maxAmountMinor, String currency) {
        throw GatewayException.unsupported(provider());
    }
}
