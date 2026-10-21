package com.nabd.hms.messaging;

/** COM-003: a provider-call class (OTP, sends, template admin) is at its concurrency or rate limit. Nothing was sent;
 * outbox callers just throw it on and the event is retried with backoff. */
class ProviderBusyException extends RuntimeException {
    ProviderBusyException(String callClass) {
        super("provider call class '" + callClass + "' is at its concurrency/rate limit");
    }
}
