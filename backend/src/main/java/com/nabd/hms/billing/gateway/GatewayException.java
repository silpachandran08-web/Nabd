package com.nabd.hms.billing.gateway;

/** A gateway call failed. The message is a fixed reason code, never provider text (it could echo a key or a name). */
public class GatewayException extends RuntimeException {

    public static final String INVALID_CREDENTIALS = "invalid_credentials";
    public static final String UNAVAILABLE = "unavailable";
    public static final String UNSUPPORTED = "unsupported";

    private final String reason;

    public GatewayException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }

    static GatewayException unsupported(String provider) {
        return new GatewayException(UNSUPPORTED);
    }
}
