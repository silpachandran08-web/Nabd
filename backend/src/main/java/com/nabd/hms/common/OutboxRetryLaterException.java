package com.nabd.hms.common;

/** OPS-012: thrown by an outbox handler that cannot proceed yet (e.g. another worker's send is still in flight) and
 * knows when to look again; the event goes back to pending with next_attempt_at = now + delaySeconds. */
public class OutboxRetryLaterException extends RuntimeException {

    private final long delaySeconds;

    public OutboxRetryLaterException(String reason, long delaySeconds) {
        super(reason);
        this.delaySeconds = delaySeconds;
    }

    public long delaySeconds() {
        return delaySeconds;
    }
}
